package com.maidmod.selftalk;

/**
 * 历史上下文模式（玩家级设置，作用于其名下全部女仆）。
 * <p>
 * 持久化与协议一律用 {@link #id()} 字符串，<b>不得</b>用枚举 ordinal 或本地化文字——
 * 文案会调整，只有存储值是稳定身份。
 * <p>
 * 三种模式都保留完整人设、语言与输出格式要求、自定义 Prompt、当前适用的环境信息，
 * 以及互聊需要回应的对方发言；差别只在「注入哪些历史」：
 * <ul>
 *   <li>{@link #FULL}：默认模式，保留原有整段历史组装，行为与改动前一致；</li>
 *   <li>{@link #COMPACT}：不注入玩家聊天原文，用已有摘要 + 最近一条自话 + 当前互聊窗口；</li>
 *   <li>{@link #RETRIEVAL}：静默关键词规划 + 本地 BM25，最多召回三个历史块，不发全量玩家聊天历史。</li>
 * </ul>
 * 循环顺序固定 {@code FULL → COMPACT → RETRIEVAL → FULL}（见 {@link #next()}）。
 */
public enum HistoryContextMode {

    /** 全量：原有历史组装路径 */
    FULL("full"),
    /** 精简：摘要 + 最近一条自话 + 互聊窗口，不含玩家聊天原文 */
    COMPACT("compact"),
    /** 检索：关键词规划 + BM25 召回最多三个历史块 */
    RETRIEVAL("retrieval");

    private final String id;

    HistoryContextMode(String id) {
        this.id = id;
    }

    /** 稳定存储／协议值 */
    public String id() {
        return id;
    }

    /** 是否为默认模式（默认值不落盘，见 {@link PlayerSettingsStore}） */
    public boolean isDefault() {
        return this == FULL;
    }

    /** 下一态（按钮点击的循环顺序） */
    public HistoryContextMode next() {
        return switch (this) {
            case FULL -> COMPACT;
            case COMPACT -> RETRIEVAL;
            case RETRIEVAL -> FULL;
        };
    }

    /** 按存储值解析，非法值（含 null）返回 null，由调用方决定回退口径 */
    public static HistoryContextMode fromId(String id) {
        if (id == null) {
            return null;
        }
        for (HistoryContextMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return null;
    }
}
