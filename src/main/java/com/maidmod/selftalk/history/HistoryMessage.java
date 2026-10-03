package com.maidmod.selftalk.history;

/**
 * 检索库侧的历史消息视图（本包不依赖 Minecraft / TLM 类型，可脱离游戏自检）。
 * <p>
 * {@code role} 用 TLM {@code Role} 枚举的 {@code name()}（SYSTEM / USER / ASSISTANT / TOOL /
 * DEVELOPER），指纹拼接据此与 {@code SelfTalkProvenance} 保持同一字符串形态。
 */
public record HistoryMessage(String role, String text, long gameTime, boolean hasToolCalls) {

    public static final String ROLE_SYSTEM = "SYSTEM";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_TOOL = "TOOL";

    /** 普通玩家消息（主人与女仆的对话，入库的唯一入口） */
    public boolean isUser() {
        return ROLE_USER.equals(role);
    }

    /** 普通女仆回复（工具过程消息由 {@link #hasToolCalls()} 另行排除） */
    public boolean isAssistant() {
        return ROLE_ASSISTANT.equals(role);
    }

    /** 系统消息（人设、摘要）：既不入库也不参与分块 */
    public boolean isSystem() {
        return ROLE_SYSTEM.equals(role);
    }

    /** 工具结果消息：跳过，且不切断对话块 */
    public boolean isTool() {
        return ROLE_TOOL.equals(role);
    }

    public String textOrEmpty() {
        return text == null ? "" : text;
    }

    /** 本消息的稳定指纹（与 {@code SelfTalkProvenance.fingerprint} 同一算法） */
    public String fingerprint() {
        return HistoryFingerprint.of(role, textOrEmpty(), gameTime);
    }
}
