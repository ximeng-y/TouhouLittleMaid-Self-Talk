package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 互聊专用的 LLM 回调：最终响应的一切副作用统一在服务端主线程执行，并<b>先检查链／请求许可</b>。
 * <p>
 * 本轮请求身份 {@link #request} 在发送前经 {@link InterChatRequest#attachFormalCallback} 绑定，
 * 回调与其请求一一对应。主人插话中断后：
 * <ul>
 *   <li>被作废的请求（{@link InterChatRequest#isCancelled()}）：迟到成功/失败任何结果都不产生
 *       玩家可见副作用——不显示、不播报、不广播、不写窗口、不同步对方、不抽连续概率、
 *       不续接下一轮；只清理本请求自己持有的资源（工具历史、pending、等待气泡、会话、链对锁），
 *       绝不触碰其它轮次/新请求的 pending、气泡或对锁；</li>
 *   <li>未被作废但链已中断的被动参与者：正式回复请求既已发出则照常显示与播报，但不得续接下一轮；</li>
 *   <li>双方都不再派发下一轮。</li>
 * </ul>
 * 工具续接在同一许可下收紧：作废后不再执行新的工具批次、不再刷新气泡；父类异步续接也被挡住。
 * <p>
 * 失败路径<b>不调父类 {@code onFailure}</b>——TLM 的父类实现会重新提交一个服务端任务去展示红色错误
 * 文本并移除等待气泡，对互聊请求意味着「失败也产生可见输出」。这里在<b>当前任务</b>（回调已运行于
 * 服务端主线程或正在投递）内复用 TLM 的错误文本生成逻辑，先经请求许可判定，
 * 若被作废则只清资源、绝不显示。
 */
public class InterChatCallback extends LLMCallback {

    private final EntityMaid peer;
    private final String peerText;
    private final double broadcastRange;
    private final boolean isResponder;
    /** 本条消息在互聊链上的序号（发起者消息为 1），用于链长护栏 */
    private final int chainRound;
    /** 本次互聊所属的链（回调持有直接引用：链释放后仍可按中断状态判定交付许可） */
    private final InterChatChain chain;
    /** 本轮请求身份（正式派发时绑定；为 null 表示回调早于请求登记——正常互聊流程不应出现） */
    private final InterChatRequest request;
    /**
     * 本轮工具过程写入 TLM 历史的消息引用（与 SelfTalkCallback 同构）。
     * 最终回答后成对全删——assistant(tool_calls) 与 tool 结果必须配对删除，
     * 孤立记录会让后续请求被 LLM 服务端 400 拒绝。
     * 注：TLM 1.5.3 的 HistoryMessagesCheck 已含 removeUnpairedToolCalls（能剥离未配对 tool_calls
     * 并删除其后孤儿 TOOL），但每次发送前的清洗依赖 TLM 实现细节，本 mod 直接成对删除更稳妥，
     * 且能覆盖「窗口裁剪切对」这类 TLM 清洗不到的边界。
     */
    private final List<LLMMessage> toolHistoryMessages = new ArrayList<>();

    public InterChatCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                             EntityMaid peer, String peerText,
                             double broadcastRange, boolean isResponder, int chainRound, boolean toolEnabled,
                             InterChatChain chain, InterChatRequest request) {
        super(chatManager, messages);
        this.peer = peer;
        this.peerText = peerText;
        this.broadcastRange = broadcastRange;
        this.isResponder = isResponder;
        this.chainRound = chainRound;
        this.chain = chain;
        this.request = request;
        this.needAddTools = toolEnabled;
    }

    /**
     * 本次请求是否已被业务作废：迟到结果（成功/失败/工具连锁）必须沉默清理，不产生任何可见副作用。
     * 公开供 {@link com.maidmod.selftalk.mixin.InterChatToolLifecycleMixin} 在响应线程读取。
     */
    public boolean isCancelled() {
        return request != null && request.isCancelled();
    }

    /**
     * 本次请求是否仍允许继续（含工具续接与新请求派发）。
     * <p>
     * 判据是「请求未被作废 且 链未被中断」：被插话后另一只女仆的输出可以照发，
     * 但同样不得再续接下一轮或发起工具批次。
     */
    private boolean isStillAllowed() {
        return !isCancelled() && (chain == null || chain.canChain());
    }

    /** 工具轮次：请求作废/链中断后不再执行新的工具批次；父类写 assistant(tool_calls) 历史后捕获队头引用 */
    @Override
    public void onFunctionCall(Message choice, LLMClient client) {
        if (!isStillAllowed()) {
            // 被动参与者仍可交付已发出的普通文本，但丢弃工具响应后不会再有成功/失败回调。
            // 必须主动结束本轮，不能让 pending、等待气泡和上游登记继续等待超时。
            Runnable finish = () -> {
                if (request != null) {
                    request.cancel();
                } else {
                    AgentTweaksLifecycleBridge.cancel(this);
                    cancelLocally();
                }
            };
            if (isOnServerThread()) {
                finish.run();
            } else {
                runOnServerThread(finish);
            }
            return;
        }
        super.onFunctionCall(choice, client);
        captureToolHistoryHead(Role.ASSISTANT);
    }

    /** 工具结果：捕获队头引用并刷新互聊 pending 心跳（超时语义改为「最后一次工具活动后 5 分钟」） */
    @Override
    public LLMCallback addToolResult(String result, String toolId) {
        if (isCancelled()) {
            // 作废后工具结果不再写入历史、不再刷新心跳：本回调即将收敛，不应留下任何可配对记录
            return this;
        }
        LLMCallback cb = super.addToolResult(result, toolId);
        captureToolHistoryHead(Role.TOOL);
        refreshPendingHeartbeat();
        return cb;
    }

    private void captureToolHistoryHead(Role expected) {
        LLMMessage head = getChatManager().getHistory().getDeque().peekFirst();
        if (head != null && head.role() == expected) {
            toolHistoryMessages.add(head);
        }
    }

    /** 最终回答（或失败）后删除本轮全部工具过程消息（成对删除，绝不留下孤立半截记录） */
    private void discardToolHistory() {
        if (toolHistoryMessages.isEmpty()) {
            return;
        }
        getChatManager().getHistory().getDeque().removeAll(toolHistoryMessages);
        toolHistoryMessages.clear();
    }

    /** 工具轮次心跳：刷新互聊 pending 起始 tick（须在服务端主线程写状态） */
    private void refreshPendingHeartbeat() {
        Runnable beat = () -> {
            // 归属校验：只刷新本请求自己的 pending 计时；被作废的旧请求不得经 get() 重建状态、
            // 也不得更新新请求的计时
            SelfTalkState.State state = SelfTalkState.peek(getMaid().getId());
            if (state != null && state.interChatPending
                    && (request == null || state.currentInterChatRequest == request)) {
                state.interChatPendingSinceTick = getMaid().level().getServer().getTickCount();
            }
        };
        if (isOnServerThread()) {
            beat.run();
        } else {
            runOnServerThread(beat);
        }
    }

    /**
     * 清理自己持有的等待气泡（按<b>本轮捕获的 id</b>精准删除，绝不误删新请求的气泡）。
     */
    private void discardWaitingBubble(EntityMaid maid) {
        if (waitingChatBubbleId == 0) {
            return;
        }
        maid.getChatBubbleManager().removeChatBubble(waitingChatBubbleId);
    }

    /** 复位本请求的互聊 pending（服务端主线程）；只清自己持有的，不动其它轮次状态 */
    private void resetInterChatPending() {
        // 用不创建状态的查询 + 归属校验：被作废的旧请求不得清掉新请求的 pending，
        // 女仆已卸载时不得经 get() 重建刚删除的状态
        SelfTalkState.State state = SelfTalkState.peek(getMaid().getId());
        if (state == null || (request != null && state.currentInterChatRequest != request)) {
            return;
        }
        state.interChatPending = false;
        state.interChatPendingSinceTick = -1;
    }

    /**
     * 请求作废时的<b>本地收尾</b>（服务端主线程，由 {@link InterChatRequest#cancel} 同步调用）：
     * 丢弃本轮工具历史、删除等待气泡、复位 pending、解除互聊对锁。
     * <p>
     * Agent-Tweaks 的 {@code cancel} 契约上<b>不调用业务回调</b>，因此不能等待
     * {@link #onSuccess}/{@link #onFailure} 来收敛——作废即同步做完本请求自己的清理，
     * 绝不让 pending 空占 5 分钟、也不留下可见气泡或工具残留。
     * <p>
     * 只触碰本请求自己持有的资源（按 {@code currentInterChatRequest == this.request} 归属校验），
     * 绝不误清新请求的 pending、气泡或对锁。
     */
    void cancelLocally() {
        EntityMaid maid = getMaid();
        if (maid != null && maid.isAlive()) {
            discardToolHistory();
            discardWaitingBubble(maid);
        }
        resetInterChatPending();
        endChainAndComplete(null);
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // 本类不调 super.onSuccess（父类 mixin 的剥离不会进入），首行显式剥离段标签
        SegmentTags.stripResponse(responseChat);
        String chatText = responseChat.getChatText();
        String ttsText = responseChat.getTtsText();
        if (chatText.isBlank() || ttsText.isBlank()) {
            // 空回复按失败收敛（不走父类，见 onFailure 说明）
            this.onFailure(null, new Throwable("Error in Response Chat: %s".formatted(responseChat)),
                    com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode.CHAT_TEXT_IS_EMPTY);
            return;
        }
        EntityMaid maid = getMaid();
        // 交付许可在服务端主线程判定：链可能在此期间已被主人插话中断
        Runnable finish = () -> {
            if (isCancelled()) {
                // 请求作废：迟到成功不产生任何可见副作用，只清本请求自己的资源
                discardToolHistory();
                discardWaitingBubble(maid);
                resetInterChatPending();
                endChainAndComplete(null);
                return;
            }
            boolean deliver = chain == null || chain.canDeliver(maid);
            discardToolHistory();
            resetInterChatPending();
            if (!deliver) {
                // 被主人插话的目标：不显示、不播报、不广播、不写窗口、不同步对方；只清掉自己的等待气泡。
                // 链已在插话时中断：此处统一结束链（解锁 + 释放注册表），不留悬挂状态阻塞后续配对
                discardWaitingBubble(maid);
                endChainAndComplete(null);
                return;
            }
            if (AIConfig.TTS_ENABLED.get() && chatManager.getTTSSite() != null && chatManager.getTTSSite().enabled()) {
                chatManager.tts(chatManager.getTTSSite(), chatText, ttsText, waitingChatBubbleId);
            } else if (maid.level() instanceof ServerLevel serverLevel) {
                serverLevel.getServer().submit(() -> maid.getChatBubbleManager().addLLMChatText(chatText, waitingChatBubbleId));
            }
            if (isResponder && peerText != null && !peerText.isBlank()) {
                boolean needSync = true;
                if (!state().windowInterChatMsgs.isEmpty()) {
                    String last = state().windowInterChatMsgs.get(state().windowInterChatMsgs.size() - 1).message();
                    if (peerText.equals(last)) needSync = false;
                }
                if (needSync) {
                    MaidInterChatService.syncPeerMessageToWindow(maid, peerText);
                }
            }
            MaidInterChatService.addInterChatMessage(maid, chatText);
            if (peer != null && peer.isAlive() && peer.level() instanceof ServerLevel) {
                // 不重新同步回已被主人插话、已开启新聊天的对方窗口
                if (chain == null || chain.canDeliver(peer)) {
                    MaidInterChatService.syncPeerMessageToWindow(peer, chatText);
                }
            }
            broadcastToNearby(maid, chatText);
            // 链已中断（含本次为被插话的一方）时不再续接；对方不可用时链终止。
            // 交付完本次回复即结束链：解锁并释放注册表，不让旧链残留阻塞后续配对。
            // 回复已实际交付 → 正常终态：向 Agent-Tweaks 上报 complete（normalMaid 传 maid）
            if (chain != null && !chain.canChain()) {
                endChainAndComplete(maid);
                return;
            }
            if (peer != null && peer.isAlive() && peer.level() instanceof ServerLevel) {
                double prob = Config.INTER_CHAT_CHAIN_PROBABILITY.get();
                if (maid.getRandom().nextDouble() < prob) {
                    tryChain(peer, maid, chatText);
                } else {
                    // 概率抽签不续接：链自然结束，解除互聊对锁（tryChain 各提前返回路径也会解锁）
                    endChainAndComplete(maid);
                }
            } else {
                // 对方不可用（死亡/卸载/非服务端维度）：链终止，解除对锁；回复已交付仍算正常终态
                endChainAndComplete(maid);
            }
        };
        if (isOnServerThread()) {
            finish.run();
        } else {
            runOnServerThread(finish);
        }
    }

    /** 本 Callback 所在状态表条目（正常互聊流程必然存在） */
    private SelfTalkState.State state() {
        return SelfTalkState.get(getMaid().getId());
    }

    private void tryChain(EntityMaid nextSpeaker, EntityMaid lastSpeaker, String lastText) {
        if (!(nextSpeaker.level() instanceof ServerLevel level)) { endChainAndComplete(null); return; }
        // 热重载下中途关闭互聊时立即终止在途链
        if (!Config.INTER_CHAT_ENABLED.get()) { endChainAndComplete(null); return; }
        // 链长护栏：本条消息已是链上第 chainRound 条，达到上限即结束本次互聊，
        // 防止连续概率配到 1.0 等极端配置下无限往返消耗 token
        if (chainRound >= Config.INTER_CHAT_MAX_CHAIN_ROUNDS.get()) { endChainAndComplete(null); return; }
        if (!SelfTalkHandler.hasPlayerNearby(nextSpeaker, Config.INTER_CHAT_PLAYER_RANGE.get())) { endChainAndComplete(null); return; }
        if (!isMaidNearby(nextSpeaker, lastSpeaker, Config.INTER_CHAT_MAID_RANGE.get())) { endChainAndComplete(null); return; }
        // 1.1.2 睡眠 gate：对方睡觉且玩家开启「睡觉时安静」→ 链自然结束（下一跳由 dispatchNow 顺延会挂锁
        // 到超时兜底，此处显式终止并解锁，与对方死亡/卸载同语义）
        if (nextSpeaker.isSleeping()
                && PlayerSettingsStore.isSleepQuietForMaid(nextSpeaker.level().getServer(), nextSpeaker)) {
            endChainAndComplete(null);
            return;
        }
        if (Config.PLAYER_OPTION_ENABLED.get() && !PlayerSettingsStore.isInterChatEnabledForMaid(level.getServer(), nextSpeaker)) { endChainAndComplete(null); return; }
        // 主人插话/请求作废可能在上方任一步之间发生：派发前再确认一次链未中断、请求未作废
        if (!isStillAllowed()) { endChainAndComplete(null); return; }
        // 链的两轮交接（第 3 轮起）：对方上一轮交付后才轮到本轮派发，此时其正式请求必然已实际
        // 发出；未发出说明上一轮请求失败或状态已漂移，本链无以为继，显式结束并释放，
        // 避免对锁悬挂到超时。首次交接（chainRound == 1，对方尚未收到过本链第一轮请求）
        // 绝不能要求对方已进入正式阶段——否则交接被永久挡住
        if (chain != null && chainRound > 1 && !chain.formalPhase(nextSpeaker)) { endChainAndComplete(null); return; }
        // 链式续接是发起者回复后的单条连续请求，天然串行、每轮隔一次 LLM 往返，
        // 不走 5~8s 全局节流桶（发起者派发已占用该桶），否则 responder 路径永远被退避。
        // 若对方忙（自话/玩家 chat 在途），dispatcher 会顺延本次回应；对锁保持，链仅暂停不终止。
        SelfTalkDispatcher.requestInterChatResponder(nextSpeaker, lastSpeaker, lastText, broadcastRange,
                chainRound + 1, chain == null ? 0 : chain.id());
        // 本轮已成功交付并交棒给下一轮：解除本轮请求登记（成功后续接同属正常终态），
        // 释放 session/回调引用；链不释放（仍在续接），对锁保持由后续轮次维护
        if (request != null && !request.isCancelled()) {
            request.completeLocally();
        }
    }

    /**
     * 链自然结束：解除本对锁并释放链召回缓存。
     * <p>
     * {@code normalMaid} 为本轮正常走完业务终态（成功交付或概率不续接）的女仆——仅此路径向
     * Agent-Tweaks 上报 {@code complete}；作废/失败/被动清退等一律不上报（迟到或失败不算「正常完成」）。
     */
    private void endChainAndComplete(EntityMaid normalMaid) {
        unlockPair();
        InterChatChain.release(chain);
        // 正常终态统一解除本轮请求登记（成功交付、概率不续接、失败收敛、被动清退、链中断不续接）：
        // 作废请求已由 cancel() 解除登记，此处被 isCancelled 守卫跳过，不会重复清理。
        // 不释放仍需续接的整条链——链的续接/释放由回调按链状态决定。
        if (request != null && !request.isCancelled()) {
            if (normalMaid != null && request.isFormalDispatched()) {
                request.completeFormal();
            }
            request.completeLocally();
        }
    }

    /** 解除本回调双方（maid 与 peer）的互聊对锁：仅当双方当前仍互为配对<b>且该配对仍归属本链</b>时才解除——
     * 链过期后 peer 可能已被第三方锁定新链，无条件解锁会误拆无关在途链（与 dispatcher 失败路径同语义） */
    private void unlockPair() {
        if (peer != null) {
            SelfTalkDispatcher.unlockPairIfPaired(getMaid(), peer,
                    getMaid().level().getServer().getTickCount(), chain == null ? 0 : chain.id());
        }
    }

    private boolean isMaidNearby(EntityMaid a, EntityMaid b, double range) {
        if (a.level() != b.level()) return false;
        return a.distanceToSqr(b) <= range * range;
    }

    private void broadcastToNearby(EntityMaid maid, String chatText) {
        if (!(maid.level() instanceof ServerLevel serverLevel)) return;
        // 与 SelfTalkCallback 一致：TLM addLLMChatText 已给主人发过同格式消息，此处跳过主人避免重复
        UUID ownerUuid = maid.getOwnerUUID();
        Component message = Component.literal("<").append(maid.getName()).append("> ").append(chatText).withStyle(ChatFormatting.GRAY);
        AABB box = maid.getBoundingBox().inflate(broadcastRange);
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, box,
                p -> p.isAlive() && !p.isSpectator() && !p.getUUID().equals(ownerUuid))) {
            player.sendSystemMessage(message);
        }
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        // 不调父类：父类会重新提交服务端任务展示红色错误文本，对互聊意味着「失败也产生可见输出」。
        // 这里在回调当前上下文内（服务端主线程或经 runOnServerThread 投递）先判请求许可：
        // 被作废 → 只清资源绝不显示；未被作废 → 复用 TLM 的错误文本生成逻辑后收敛。
        Runnable finish = () -> {
            if (isCancelled()) {
                discardToolHistory();
                discardWaitingBubble(getMaid());
                resetInterChatPending();
                endChainAndComplete(null);
                return;
            }
            // 复用 TLM 父类 onFailure 的文本生成（红色错误 + 收起等待气泡），但全程在本任务内完成
            discardToolHistory();
            EntityMaid maid = getMaid();
            // 复用 TLM 父类 onFailure 的文本生成：红色错误文本发送给主人 + 收起该回调持有的等待气泡。
            // 全程在当前任务内完成，不再另投递服务端任务（父类实现会再 submit 一次）
            if (maid.getOwner() instanceof net.minecraft.server.level.ServerPlayer player) {
                String cause = throwable.getLocalizedMessage();
                net.minecraft.network.chat.MutableComponent errorMessage =
                        com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode.getErrorMessage(
                                com.github.tartaricacid.touhoulittlemaid.ai.service.ServiceType.LLM, errorCode, cause);
                player.sendSystemMessage(errorMessage.withStyle(net.minecraft.ChatFormatting.RED));
            }
            maid.getChatBubbleManager().removeChatBubble(waitingChatBubbleId);
            resetInterChatPending();
            // 请求失败即链终止：解除互聊对锁并释放链（仅当双方仍互为配对才解锁，防误拆第三方新链锁）。
            // 被主人插话的目标同样要清掉自己的等待气泡——本路径不产生任何可见输出
            if (chain != null && !chain.canDeliver(maid)) {
                discardWaitingBubble(maid);
            }
            endChainAndComplete(null);
        };
        if (isOnServerThread()) {
            finish.run();
        } else {
            runOnServerThread(finish);
        }
    }
}
