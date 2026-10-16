package com.maidmod.selftalk;

/**
 * 女仆的人设模式：每只女仆独立选择「TLM 原版人设」或「四栏人设」，默认原版。
 * <p>
 * 两种模式的人设内容分别保存、互不复制也互不覆盖：本枚举只表达「本轮用哪一套」，
 * 切换模式不改变任何一套的内容。
 * <p>
 * 持久化用 {@link #tag()} 给出的稳定字符串（不写序号，避免枚举顺序变化导致旧档错读）；
 * 缺失或未知值一律回退 {@link #ORIGINAL}。
 */
public enum MaidPersonaMode {

    /** TLM 原版整段人设（{@code customSetting} 或默认设定文件） */
    ORIGINAL("original"),

    /** 本 mod 的四栏人设（身份／性格固定注入，背景／爱好按需召回） */
    STRUCTURED("structured");

    private final String tag;

    MaidPersonaMode(String tag) {
        this.tag = tag;
    }

    /** 持久化用字符串（小写英文） */
    public String tag() {
        return tag;
    }

    /** 是否四栏模式 */
    public boolean isStructured() {
        return this == STRUCTURED;
    }

    /** 由持久化字符串解析；null、空白或未知值一律按 {@link #ORIGINAL} */
    public static MaidPersonaMode fromTag(String value) {
        if (value == null) {
            return ORIGINAL;
        }
        for (MaidPersonaMode mode : values()) {
            if (mode.tag.equals(value)) {
                return mode;
            }
        }
        return ORIGINAL;
    }
}
