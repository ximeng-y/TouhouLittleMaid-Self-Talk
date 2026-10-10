package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.HistoryMessagesCheck;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 女仆互聊服务：发起、回应与互聊窗口维护。
 * <p>
 * 一次连续互聊由显式链标识（{@link InterChatChain}）贯串：链上每只女仆只在<b>自己第一次发言</b>
 * 时执行关键词规划与 BM25，之后复用同一条链内自己那份召回结果；链结束即释放。
 * <p>
 * 历史组装走 {@link HistoryContextSession}，与自话同构——三种历史上下文模式在互聊路径同样生效，
 * 互聊窗口与需要回应的对方发言在三种模式下都原样注入。
 */
public final class MaidInterChatService {
    private MaidInterChatService() {}

    /** 发起者消息为链上第 1 条 */
    public static boolean triggerInitiator(EntityMaid initiator, EntityMaid responder,
                                           double broadcastRange, InterChatChain chain) {
        return triggerInternal(initiator, responder, null, null, false, broadcastRange, 1, chain);
    }

    /** 回答者（链式续接），沿用本链的 chainId；{@code peerMessageId} 为对方那条发言的消息身份 */
    public static boolean triggerResponder(EntityMaid responder, EntityMaid initiator, UUID peerMessageId,
                                           String peerText, double broadcastRange, int chainRound,
                                           InterChatChain chain) {
        return triggerInternal(responder, initiator, peerMessageId, peerText, true, broadcastRange,
                chainRound, chain);
    }

    private static boolean triggerInternal(EntityMaid maid, EntityMaid peer, UUID peerMessageId, String peerText,
                                           boolean isResponder, double broadcastRange, int chainRound,
                                           InterChatChain chain) {
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null) return false;
        if (!AIConfig.LLM_ENABLED.get()) return false;
        LLMSite site = chatManager.getLLMSite();
        if (site == null || !site.enabled()) return false;
        if (chatManager.customSetting.isBlank() && chatManager.getSetting().isEmpty()) return false;
        if (chain == null) return false;
        String language = SelfTalkContexts.sanitizeLanguage(StringUtils.isBlank(chatManager.chatLanguage) ? Config.SELF_TALK_LANGUAGE.get() : chatManager.chatLanguage);
        List<LLMMessage> messages = SelfTalkContexts.fetchCleanedMessages(chatManager, language, "inter-chat");
        if (messages == null) return false;
        int historyCount = messages.size();
        int windowCount = 0;
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        List<LLMMessage> windowCopy = new ArrayList<>(state.windowInterChatMsgs.size());
        for (SelfTalkState.InterChatWindowEntry entry : state.windowInterChatMsgs) {
            windowCopy.add(entry.message());
        }
        for (LLMMessage wm : windowCopy) { messages.add(wm); }
        windowCount += windowCopy.size();
        if (isResponder && peerText != null && !peerText.isBlank()) {
            // 按消息身份判定是否已在窗口里（本轮之前可能已由对方投递写入）；
            // 不能按正文相等判定——双方重复说出相同文字属于不同消息
            boolean alreadyInWindow = peerMessageId != null && containsMessageId(state, peerMessageId);
            if (!alreadyInWindow) {
                messages.add(LLMMessage.assistantChat(maid, peerText));
                windowCount++;
            }
        }
        String prompt = isResponder ? SelfTalkPrompts.INTER_CHAT_RESPONDER : SelfTalkPrompts.INTER_CHAT_INITIATOR;
        // 拼装顺序与自话同构：硬编码提示词 + 语言指令 + Tool 策略段(互聊约束行) + 自定义 Prompt
        prompt = prompt + SelfTalkContexts.languageInstruction(language)
                + SelfTalkContexts.toolPolicyBlock(maid, language, true)
                + SelfTalkContexts.customPromptBlock(maid, language);
        // 清洗先于上下文构建：checkMessages 失败会放弃本次触发，感知事件不能在此之前被 drain 消费。
        // 构建后不得再追加任何上下文段——统一入口内部已含固定前缀、情境与感知段及 <context> 包装。
        try { HistoryMessagesCheck.checkMessages(messages); } catch (Throwable t) { MaidSelfTalkMod.LOGGER.warn("HistoryMessagesCheck after prompt failed for inter-chat, skipped", t); return false; }
        // 历史上下文模式（玩家级，作用于其名下所有女仆）：链上每位参与者沿用自己本次会话冻结的模式
        HistoryContextMode mode = PlayerSettingsStore.getHistoryContextModeForMaid(maid.level().getServer(), maid);
        SelfTalkContexts.EnvironmentSnapshot environment =
                SelfTalkContexts.collectEnvironment(maid, language);
        // 有效自话的按序合并不在这里做：切出的历史区<b>只含 TLM 玩家历史</b>，
        // 三种模式下的合并与来源登记统一由 {@link HistoryContextSession} 完成，
        // 避免同一批自话被注入两次
        SelfTalkHistoryAssembler.HistoryLayout layout =
                SelfTalkHistoryAssembler.split(messages, historyCount, windowCount);
        // Tool 判定在派发时取（dispatcher 顺延队列是延迟派发的，入队时不判定）
        boolean toolEnabled = PlayerSettingsStore.isToolCallEnabledForMaid(maid.level().getServer(), maid);
        InterChatChain.Participant participant = chain.participant(maid);
        // 本轮请求身份：在会话开始前登记为本女仆「当前」互聊请求（规划/正式回调共用）。
        // 主人插话、超时、死亡/卸载借它作废在途请求；请求登记强制覆盖旧条目——同一女仆同一时刻
        // 至多一个在途互聊请求，新轮请求压着旧轮（旧轮的迟到回调按自身停止标记沉默，不误伤新轮）
        InterChatRequest request = InterChatRequest.registerFor(maid.getId(), chain.id(), chain);
        chain.registerRequest(maid, request);
        HistoryContextSession session = new HistoryContextSession(maid, chatManager, site, language,
                prompt, mode, environment, layout.systemPrefix(), layout.history(), layout.window(),
                // 链上首次检索结果：已完成（含「结果为空」）则直接复用，不再规划、不再跑 BM25
                participant.cachedRecall(),
                // 主人插话中断/请求作废后一切未发出的动作停止：规划结果不再触发正式生成、
                // 迟到结果不再派发
                () -> !chain.interrupted() && !request.isCancelled(),
                // 正式派发（服务端主线程）：把本轮请求身份带上，标记正式阶段并登记回调
                prepared -> dispatchInterChat(maid, chatManager, site, prepared, peer, peerMessageId, peerText,
                        broadcastRange, isResponder, chainRound, toolEnabled, chain, request),
                // 检索成品回报：链上后续轮次据此判断「首次检索已完成」（空结果同样算完成）
                blocks -> recordChainRecall(chain, maid, blocks),
                null,
                // 会话被作废时（检索模式规划请求的终止入口）把请求一并作废：
                // 请求的 cancel 再回来终止本会话——两者互相幂等，收敛一致
                () -> request.cancel());
        // 把会话绑定到请求身份：请求作废时经会话终止在途规划请求（silent-cancel），
        // 晚到的规划结果不得再导出纠正或正式生成
        request.attachSession(session);
        session.start();
        // 检索模式的规划与建索引是异步的：此时正式请求可能尚未发出，但本次触发已受理；
        // 正式阶段标记在实际派发点（dispatchInterChat）完成，链交接以真实派发为准
        return true;
    }

    /**
     * 派发互聊请求：置 pending、登记链上首次召回结果、提交给 LLM 客户端。
     * <p>
     * 由 {@link HistoryContextSession} 在正式生成那一刻回调；被插话中断时不会走到这里。
     * <p>
     * 同步报文阶段就抛异常（site.url 非法等）时，本轮请求身份一并作废：会话、在途规划、
     * 已登记回调与 pending 全部收束，链释放——异常后的请求绝不会留下可派发回调，
     * 否则该身份会被后续迟到结果误用。
     */
    private static boolean dispatchInterChat(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                             List<LLMMessage> messages, EntityMaid peer, UUID peerMessageId,
                                             String peerText,
                                             double broadcastRange, boolean isResponder, int chainRound,
                                             boolean toolEnabled, InterChatChain chain, InterChatRequest request) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.interChatPending = true;
        state.interChatPendingSinceTick = maid.level().getServer().getTickCount();
        InterChatCallback callback = new InterChatCallback(chatManager, messages, peer, peerMessageId, peerText,
                broadcastRange, isResponder, chainRound, toolEnabled, chain, request,
                SelfTalkRequestToken.capture(maid.getId()));
        // 构造器已创建等待气泡：先登记资源，再调用可能同步失败的外部客户端。
        request.attachFormalCallback(callback);
        try {
            site.client().chat(callback);
            // 正式请求已实际发出：此刻才标记进入正式阶段（关键词规划／建索引阶段不算），
            // 链上第 3 轮起的交接校验以此为准；回调资源已在发送前绑定
            chain.markFormalPhase(maid);
            request.markFormalDispatched();
        } catch (Throwable t) {
            // 同步异常：本轮请求从未真正发出，作废身份 + 复位 pending + 释放链，不留悬挂
            state.interChatPending = false;
            state.interChatPendingSinceTick = -1;
            request.cancel();
            InterChatChain.release(chain);
            MaidSelfTalkMod.LOGGER.warn("Failed to dispatch inter-chat request for maid {}", maid.getId(), t);
            return false;
        }
        return true;
    }

    /**
     * 登记本次请求采用的召回块（链上首次检索的成品结果）。
     * <p>
     * 由 {@link HistoryContextSession} 在检索完成时回调，同一女仆同一链只生效一次；
     * 「结果为空」同样是成品，不能靠空列表判断需要重新规划。
     */
    public static void recordChainRecall(InterChatChain chain, EntityMaid maid, List<List<LLMMessage>> blocks) {
        if (chain != null && maid != null) {
            chain.completeRecall(maid, blocks);
        }
    }

    /**
     * 本女仆自己说出的一句话：<b>归档 + 写入运行时窗口</b>，返回新建的档案记录。
     * <p>
     * 顺序不可颠倒：先归档拿到消息身份，再写窗口——窗口条目带着同一条消息的 UUID，
     * 后续入站补齐与去重都按身份判定。本方法只写本人视角，对方视角由
     * {@link #deliverToPeer} 按同一消息身份写入。
     *
     * @return 新建的档案记录；女仆无可写档案时返回 null（调用方据此放弃续接）
     */
    public static AutonomousChatRecord addInterChatMessage(EntityMaid maid, String text) {
        return archive(maid, maid.getUUID(), AutonomousChatHistoryHost.displayNameOf(maid), text);
    }

    /**
     * 把一条消息（身份已定）投递给对方：写入对方档案与对方运行时窗口。
     * <p>
     * 用的是<b>同一条消息身份</b>——双方档案里这条消息 UUID 相同、顺序号按各自视角分别分配；
     * 对方被主人插话后不得为「双方记录一致」而补写它未收到的发言（由调用方判定许可）。
     */
    public static void deliverToPeer(EntityMaid peer, AutonomousChatRecord message) {
        if (peer == null || message == null) {
            return;
        }
        AutonomousChatHistory archive = AutonomousChatHistoryHost.of(peer);
        if (archive == null) {
            return;
        }
        AutonomousChatRecord copy = archive.appendCopy(message);
        if (copy != null) {
            addWindowEntry(peer, copy);
        }
    }

    /**
     * 补齐需要回应的对方发言到本人运行时窗口（只补窗口，<b>不再归档</b>——
     * 该发言已由对方或它的投递写进本人档案）。
     * <p>
     * 按消息身份判定是否已在窗口里：同一条消息重复补齐无意义，
     * 而重复说出相同文字属于不同消息，必须各自保留。
     */
    public static void syncPeerMessageToWindow(EntityMaid maid, UUID messageId, String peerText) {
        if (peerText == null || peerText.isBlank() || messageId == null) return;
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        if (containsMessageId(state, messageId)) {
            return;
        }
        state.windowInterChatMsgs.add(new SelfTalkState.InterChatWindowEntry(messageId,
                LLMMessage.assistantChat(maid, peerText)));
        trimInterChatWindow(state);
    }

    /** 把一条档案记录对应的消息写进本人运行时窗口 */
    private static void addWindowEntry(EntityMaid maid, AutonomousChatRecord record) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.windowInterChatMsgs.add(new SelfTalkState.InterChatWindowEntry(record.id(),
                LLMMessage.assistantChat(maid, record.chatText())));
        trimInterChatWindow(state);
    }

    /** 该消息身份是否已在本人运行时窗口中 */
    private static boolean containsMessageId(SelfTalkState.State state, UUID messageId) {
        if (messageId == null) {
            return false;
        }
        for (SelfTalkState.InterChatWindowEntry entry : state.windowInterChatMsgs) {
            if (messageId.equals(entry.id())) {
                return true;
            }
        }
        return false;
    }

    /** 归档一条互聊消息并返回记录（无档案时为 null） */
    private static AutonomousChatRecord archive(EntityMaid maid, UUID speakerId, String speakerName, String text) {
        AutonomousChatHistory archive = AutonomousChatHistoryHost.of(maid);
        if (archive == null) {
            return null;
        }
        AutonomousChatRecord record = archive.append(AutonomousChatRecord.Source.INTER_CHAT, speakerId, speakerName,
                maid.level().getGameTime(), text, text);
        addWindowEntry(maid, record);
        return record;
    }

    /**
     * 玩家主动 chat 时（normalChat HEAD，TLM 刚构建完 [system 设定, 摘要, ...历史]、尚未 append 玩家新消息）
     * 把互聊窗口内容拼接进本次请求的 messages，使玩家 chat 上下文 = 对话历史（含自话）+ 互聊记录 + 玩家的话。
     * <p>
     * 注入的是窗口副本；窗口本体随后在 TAIL 的 {@code onPlayerChatStart} 中清空
     * （打断连续、计数重新开始），互聊记录不写 TLM 历史（不出现在历史聊天记录界面）。
     */
    public static void injectPlayerChatContext(EntityMaid maid, List<LLMMessage> messages) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        if (state.windowInterChatMsgs.isEmpty()) {
            return;
        }
        for (SelfTalkState.InterChatWindowEntry entry : state.windowInterChatMsgs) {
            messages.add(entry.message());
        }
    }

    /** 超出 keepRounds 轮时仅保留最近 1 条消息（与自言自语「仅保留最近一次」的抛弃逻辑一致） */
    private static void trimInterChatWindow(SelfTalkState.State state) {
        int keepRounds = Config.INTER_CHAT_KEEP_ROUNDS.get();
        int rounds = (state.windowInterChatMsgs.size() + 1) / 2;
        if (rounds >= keepRounds && state.windowInterChatMsgs.size() > 1) {
            SelfTalkState.InterChatWindowEntry last =
                    state.windowInterChatMsgs.get(state.windowInterChatMsgs.size() - 1);
            state.windowInterChatMsgs.clear();
            state.windowInterChatMsgs.add(last);
        }
    }
}
