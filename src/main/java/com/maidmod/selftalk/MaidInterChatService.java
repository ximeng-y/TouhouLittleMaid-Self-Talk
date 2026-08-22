package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.HistoryMessagesCheck;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.UserPromptContexts;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public final class MaidInterChatService {
    private MaidInterChatService() {}
    /** 发起者消息为链上第 1 条 */
    public static boolean triggerInitiator(EntityMaid initiator, EntityMaid responder, double broadcastRange) {
        return triggerInternal(initiator, responder, null, false, broadcastRange, 1);
    }
    public static boolean triggerResponder(EntityMaid responder, EntityMaid initiator, String peerText, double broadcastRange, int chainRound) {
        return triggerInternal(responder, initiator, peerText, true, broadcastRange, chainRound);
    }
    private static boolean triggerInternal(EntityMaid maid, EntityMaid peer, String peerText, boolean isResponder, double broadcastRange, int chainRound) {
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null) return false;
        if (!AIConfig.LLM_ENABLED.get()) return false;
        LLMSite site = chatManager.getLLMSite();
        if (site == null || !site.enabled()) return false;
        if (chatManager.customSetting.isBlank() && chatManager.getSetting().isEmpty()) return false;
        String language = SelfTalkContexts.sanitizeLanguage(StringUtils.isBlank(chatManager.chatLanguage) ? Config.SELF_TALK_LANGUAGE.get() : chatManager.chatLanguage);
        List<LLMMessage> messages = SelfTalkContexts.fetchCleanedMessages(chatManager, language, "inter-chat");
        if (messages == null) return false;
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        List<LLMMessage> windowCopy = new ArrayList<>(state.windowInterChatMsgs);
        for (LLMMessage wm : windowCopy) { messages.add(wm); }
        if (isResponder && peerText != null && !peerText.isBlank()) {
            boolean alreadyInWindow = !windowCopy.isEmpty() && windowCopy.get(windowCopy.size() - 1).message().equals(peerText);
            if (!alreadyInWindow) {
                messages.add(LLMMessage.assistantChat(maid, peerText));
            }
        }
        String prompt = isResponder ? SelfTalkPrompts.INTER_CHAT_RESPONDER : SelfTalkPrompts.INTER_CHAT_INITIATOR;
        prompt = prompt + SelfTalkContexts.languageInstruction(language) + SelfTalkContexts.buildRandomContext(maid);
        String fullPrompt = UserPromptContexts.addContext(maid, prompt);
        messages.add(LLMMessage.userChat(maid, fullPrompt));
        try { HistoryMessagesCheck.checkMessages(messages); } catch (Throwable t) { MaidSelfTalkMod.LOGGER.warn("HistoryMessagesCheck after prompt failed for inter-chat, skipped", t); return false; }
        state.interChatPending = true;
        state.interChatPendingSinceTick = maid.level().getServer().getTickCount();
        LLMClient client = site.client();
        InterChatCallback callback = new InterChatCallback(chatManager, messages, peer, peerText, broadcastRange, isResponder, chainRound);
        try { client.chat(callback); } catch (Throwable t) { state.interChatPending = false; state.interChatPendingSinceTick = -1; MaidSelfTalkMod.LOGGER.warn("Failed to dispatch inter-chat request for maid {}", maid.getId(), t); return false; }
        return true;
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
