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
        return triggerInternal(initiator, responder, null, false, broadcastRange, 1, chain);
    }

    /** 回答者（链式续接），沿用本链的 chainId */
    public static boolean triggerResponder(EntityMaid responder, EntityMaid initiator, String peerText,
                                           double broadcastRange, int chainRound, InterChatChain chain) {
        return triggerInternal(responder, initiator, peerText, true, broadcastRange, chainRound, chain);
    }

    private static boolean triggerInternal(EntityMaid maid, EntityMaid peer, String peerText, boolean isResponder,
                                           double broadcastRange, int chainRound, InterChatChain chain) {
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
        List<LLMMessage> windowCopy = new ArrayList<>(state.windowInterChatMsgs);
        for (LLMMessage wm : windowCopy) { messages.add(wm); }
        windowCount += windowCopy.size();
        if (isResponder && peerText != null && !peerText.isBlank()) {
            boolean alreadyInWindow = !windowCopy.isEmpty() && windowCopy.get(windowCopy.size() - 1).message().equals(peerText);
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
        SelfTalkHistoryAssembler.HistoryLayout layout =
                SelfTalkHistoryAssembler.split(messages, historyCount, windowCount);
        // Tool 判定在派发时取（dispatcher 顺延队列是延迟派发的，入队时不判定）
        boolean toolEnabled = PlayerSettingsStore.isToolCallEnabledForMaid(maid.level().getServer(), maid);
        InterChatChain.Participant participant = chain.participant(maid);
        HistoryContextSession session = new HistoryContextSession(maid, chatManager, site, language,
                prompt, mode, environment, layout.systemPrefix(), layout.history(), layout.window(),
                // 链上首次检索结果：已完成（含「结果为空」）则直接复用，不再规划、不再跑 BM25
                participant.cachedRecall(),
                // 主人插话中断后一切未发出的动作停止：规划结果不再触发正式生成、迟到结果不再派发
                () -> !chain.interrupted(),
                prepared -> dispatchInterChat(maid, chatManager, site, prepared, peer, peerText,
                        broadcastRange, isResponder, chainRound, toolEnabled, chain),
                // 检索成品回报：链上后续轮次据此判断「首次检索已完成」（空结果同样算完成）
                blocks -> recordChainRecall(chain, maid, blocks));
        // 链的交接登记先于本次会话启动：本次可能同步跑完并回调（此时自身已进入正式生成阶段，
        // 交付后判交接才不会把自己误判成「对方尚未进入正式阶段」而卡住）
        chain.markFormalPhase(maid);
        session.start();
        if (!session.settled()) {
            // 请求被插话中断或作废：不产生任何输出，链也不必再等这一轮
            chain.clearFormalPhase(maid);
        }
        return true;
    }

    /**
     * 派发互聊请求：置 pending、登记链上首次召回结果、提交给 LLM 客户端。
     * <p>
     * 由 {@link HistoryContextSession} 在正式生成那一刻回调；被插话中断时不会走到这里。
     */
    private static boolean dispatchInterChat(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                             List<LLMMessage> messages, EntityMaid peer, String peerText,
                                             double broadcastRange, boolean isResponder, int chainRound,
                                             boolean toolEnabled, InterChatChain chain) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.interChatPending = true;
        state.interChatPendingSinceTick = maid.level().getServer().getTickCount();
        InterChatCallback callback = new InterChatCallback(chatManager, messages, peer, peerText,
                broadcastRange, isResponder, chainRound, toolEnabled, chain);
        try {
            site.client().chat(callback);
        } catch (Throwable t) {
            state.interChatPending = false;
            state.interChatPendingSinceTick = -1;
            chain.clearFormalPhase(maid);
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

    public static void addInterChatMessage(EntityMaid maid, String text) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.windowInterChatMsgs.add(LLMMessage.assistantChat(maid, text));
        trimInterChatWindow(state);
    }

    public static void syncPeerMessageToWindow(EntityMaid maid, String peerText) {
        if (peerText == null || peerText.isBlank()) return;
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        if (!state.windowInterChatMsgs.isEmpty()) {
            String last = state.windowInterChatMsgs.get(state.windowInterChatMsgs.size() - 1).message();
            if (peerText.equals(last)) return;
        }
        state.windowInterChatMsgs.add(LLMMessage.assistantChat(maid, peerText));
        trimInterChatWindow(state);
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
        messages.addAll(new ArrayList<>(state.windowInterChatMsgs));
    }

    /** 超出 keepRounds 轮时仅保留最近 1 条消息（与自言自语「仅保留最近一次」的抛弃逻辑一致） */
    private static void trimInterChatWindow(SelfTalkState.State state) {
        int keepRounds = Config.INTER_CHAT_KEEP_ROUNDS.get();
        int rounds = (state.windowInterChatMsgs.size() + 1) / 2;
        if (rounds >= keepRounds && state.windowInterChatMsgs.size() > 1) {
            LLMMessage last = state.windowInterChatMsgs.get(state.windowInterChatMsgs.size() - 1);
            state.windowInterChatMsgs.clear();
            state.windowInterChatMsgs.add(last);
        }
    }
}
