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

public class InterChatCallback extends LLMCallback {

    private final EntityMaid peer;
    private final String peerText;
    private final double broadcastRange;
    private final boolean isResponder;
    /** 本条消息在互聊链上的序号（发起者消息为 1），用于链长护栏 */
    private final int chainRound;
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
                             double broadcastRange, boolean isResponder, int chainRound, boolean toolEnabled) {
        super(chatManager, messages);
        this.peer = peer;
        this.peerText = peerText;
        this.broadcastRange = broadcastRange;
        this.isResponder = isResponder;
        this.chainRound = chainRound;
        this.needAddTools = toolEnabled;
    }

    /** 工具轮次：父类写 assistant(tool_calls) 历史后捕获队头引用（CappedQueue 新消息在队头） */
    @Override
    public void onFunctionCall(Message choice, LLMClient client) {
        super.onFunctionCall(choice, client);
        captureToolHistoryHead(Role.ASSISTANT);
    }

    /** 工具结果：捕获队头引用并刷新互聊 pending 心跳（超时语义改为「最后一次工具活动后 5 分钟」） */
    @Override
    public LLMCallback addToolResult(String result, String toolId) {
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
            SelfTalkState.State state = SelfTalkState.get(getMaid().getId());
            if (state.interChatPending) {
                state.interChatPendingSinceTick = getMaid().level().getServer().getTickCount();
            }
        };
        if (isOnServerThread()) {
            beat.run();
        } else {
            runOnServerThread(beat);
        }
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // 本类不调 super.onSuccess（父类 mixin 的剥离不会进入），首行显式剥离段标签
        SegmentTags.stripResponse(responseChat);
        String chatText = responseChat.getChatText();
        String ttsText = responseChat.getTtsText();
        if (chatText.isBlank() || ttsText.isBlank()) {
            String message = "Error in Response Chat: %s".formatted(responseChat);
            this.onFailure(null, new Throwable(message), com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode.CHAT_TEXT_IS_EMPTY);
            return;
        }
        if (AIConfig.TTS_ENABLED.get() && chatManager.getTTSSite() != null && chatManager.getTTSSite().enabled()) {
            chatManager.tts(chatManager.getTTSSite(), chatText, ttsText, waitingChatBubbleId);
        } else {
            if (chatText != null && !chatText.isBlank() && maid.level() instanceof ServerLevel serverLevel) {
                serverLevel.getServer().submit(() -> maid.getChatBubbleManager().addLLMChatText(chatText, waitingChatBubbleId));
            }
        }
        EntityMaid maid = getMaid();
        Runnable finish = () -> {
            // 工具过程从历史里全部丢掉（成对删除，先于窗口同步执行）
            discardToolHistory();
            SelfTalkState.State state = SelfTalkState.get(maid.getId());
            state.interChatPending = false;
            state.interChatPendingSinceTick = -1;
            if (isResponder && peerText != null && !peerText.isBlank()) {
                boolean needSync = true;
                if (!state.windowInterChatMsgs.isEmpty()) {
                    String last = state.windowInterChatMsgs.get(state.windowInterChatMsgs.size() - 1).message();
                    if (peerText.equals(last)) needSync = false;
                }
                if (needSync) {
                    MaidInterChatService.syncPeerMessageToWindow(maid, peerText);
                }
            }
            MaidInterChatService.addInterChatMessage(maid, chatText);
            if (peer != null && peer.isAlive() && peer.level() instanceof ServerLevel) {
                MaidInterChatService.syncPeerMessageToWindow(peer, chatText);
            }
            broadcastToNearby(maid, chatText);
            if (peer != null && peer.isAlive() && peer.level() instanceof ServerLevel peerLevel) {
                double prob = Config.INTER_CHAT_CHAIN_PROBABILITY.get();
                if (maid.getRandom().nextDouble() < prob) {
                    tryChain(peer, maid, chatText);
                } else {
                    // 概率抽签不续接：链自然结束，解除互聊对锁（tryChain 各提前返回路径也会解锁）
                    unlockPair();
                }
            } else {
                // 对方不可用（死亡/卸载/非服务端维度）：链终止，解除对锁
                unlockPair();
            }
        };
        if (isOnServerThread()) {
            finish.run();
        } else {
            runOnServerThread(finish);
        }
    }

    private void tryChain(EntityMaid nextSpeaker, EntityMaid lastSpeaker, String lastText) {
        if (!(nextSpeaker.level() instanceof ServerLevel level)) { unlockPair(); return; }
        // 热重载下中途关闭互聊时立即终止在途链
        if (!Config.INTER_CHAT_ENABLED.get()) { unlockPair(); return; }
        // 链长护栏：本条消息已是链上第 chainRound 条，达到上限即结束本次互聊，
        // 防止连续概率配到 1.0 等极端配置下无限往返消耗 token
        if (chainRound >= Config.INTER_CHAT_MAX_CHAIN_ROUNDS.get()) { unlockPair(); return; }
        if (!SelfTalkHandler.hasPlayerNearby(nextSpeaker, Config.INTER_CHAT_PLAYER_RANGE.get())) { unlockPair(); return; }
        if (!isMaidNearby(nextSpeaker, lastSpeaker, Config.INTER_CHAT_MAID_RANGE.get())) { unlockPair(); return; }
        // 1.1.2 睡眠 gate：对方睡觉且玩家开启「睡觉时安静」→ 链自然结束（下一跳由 dispatchNow 顺延会挂锁
        // 到超时兜底，此处显式终止并解锁，与对方死亡/卸载同语义）
        if (nextSpeaker.isSleeping()
                && PlayerSettingsStore.isSleepQuietForMaid(nextSpeaker.level().getServer(), nextSpeaker)) {
            unlockPair();
            return;
        }
        if (Config.PLAYER_OPTION_ENABLED.get() && !PlayerSettingsStore.isInterChatEnabledForMaid(level.getServer(), nextSpeaker)) { unlockPair(); return; }
        // 链式续接是发起者回复后的单条连续请求，天然串行、每轮隔一次 LLM 往返，
        // 不走 5~8s 全局节流桶（发起者派发已占用该桶），否则 responder 路径永远被退避。
        // 若对方忙（自话/玩家 chat 在途），dispatcher 会顺延本次回应；对锁保持，链仅暂停不终止。
        SelfTalkDispatcher.requestInterChatResponder(nextSpeaker, lastSpeaker, lastText, broadcastRange, chainRound + 1);
    }

    /** 解除本回调双方（maid 与 peer）的互聊对锁：仅当双方当前仍互为配对时才解除——
     * 链过期后 peer 可能已被第三方锁定新链，无条件解锁会误拆无关在途链（与 dispatcher 失败路径同语义） */
    private void unlockPair() {
        if (peer != null) {
            SelfTalkDispatcher.unlockPairIfPaired(getMaid(), peer,
                    getMaid().level().getServer().getTickCount());
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
        super.onFailure(request, throwable, errorCode);
        runOnServerThread(() -> {
            // 失败链同样丢弃工具过程，否则历史里留下半截工具记录（孤立 tool_calls/tool → 后续 400）
            discardToolHistory();
            SelfTalkState.State state = SelfTalkState.get(getMaid().getId());
            state.interChatPending = false;
            state.interChatPendingSinceTick = -1;
            // 请求失败即链终止：解除互聊对锁（仅当双方仍互为配对才解锁，防误拆第三方新链锁）
            unlockPair();
        });
    }
}
