package com.maidmod.selftalk;

/**
 * 环境上下文单项的三态模式。
 * <p>
 * 持久化与协议一律用 {@link #id()} 字符串，<b>不得</b>用枚举 ordinal、本地化文字或目录位置——
 * 条目顺序与文案都可能调整，只有存储值是稳定身份。
 * 循环顺序固定 {@code RANDOM → ALWAYS → NEVER → RANDOM}（见 {@link #next()}）。
 */
public enum EnvironmentContextMode {

    /** 进入随机池：本轮有数据时参与全局 1~3 项抽样 */
    RANDOM("random"),
    /** 必定进入上下文：不占随机名额，只要本轮有数据就注入 */
    ALWAYS("always"),
    /** 必定不进入上下文 */
    NEVER("never");

    private final String id;

    EnvironmentContextMode(String id) {
        this.id = id;
    }

    /** 稳定存储／协议值 */
    public String id() {
        return id;
    }

    /** 下一态（按钮点击的循环顺序） */
    public EnvironmentContextMode next() {
        return switch (this) {
            case RANDOM -> ALWAYS;
            case ALWAYS -> NEVER;
            case NEVER -> RANDOM;
        };
    }

    /** 按存储值解析，非法值（含 null）返回 null，由调用方决定回退口径 */
    public static EnvironmentContextMode fromId(String id) {
        if (id == null) {
            return null;
        }
        for (EnvironmentContextMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return null;
    }
}
