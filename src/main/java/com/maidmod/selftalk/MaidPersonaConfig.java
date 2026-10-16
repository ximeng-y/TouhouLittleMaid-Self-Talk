package com.maidmod.selftalk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 一只女仆的完整人设配置：模式 + 四栏内容。
 * <p>
 * 作为<b>一次替换的整体</b>使用：模式与内容永远成对更新，不存在「模式已切到四栏、四栏内容还是上一份」
 * 或反过来的中间态。保存请求、读档、客户端同步都整份替换。
 * <p>
 * 两套内容独立存储：本类只管四栏模式这一套；TLM 原版的 {@code customSetting} 由 TLM 自己读写，
 * 两者之间不做复制、不做覆盖、不做删除。
 * <p>
 * 不可变值对象。
 */
public record MaidPersonaConfig(MaidPersonaMode mode, MaidPersonaDraft draft) {

    /** 默认配置：原版模式 + 空四栏 */
    public static final MaidPersonaConfig DEFAULT = new MaidPersonaConfig(MaidPersonaMode.ORIGINAL, MaidPersonaDraft.EMPTY);

    /**
     * 四栏模式在 AI 数据 tag 根上的独立字符串字段名。
     * <p>
     * 与四栏内容的 {@link MaidPersonaDraft#TAG_NAME} 复合标签<b>分别读写</b>：
     * 四栏为空时也必须保留已选择的四栏模式（因此模式不能靠内容标签是否存在来推断）。
     */
    public static final String KEY_MODE = "MaidSelfTalkPersonaMode";

    /** null 一律收敛到默认值（不出现 null 模式、不出现 null 草稿） */
    public MaidPersonaConfig {
        mode = mode == null ? MaidPersonaMode.ORIGINAL : mode;
        draft = draft == null ? MaidPersonaDraft.EMPTY : draft;
    }

    /** 是否四栏模式 */
    public boolean isStructured() {
        return mode.isStructured();
    }

    /** 整份替换四栏内容（模式不变） */
    public MaidPersonaConfig withDraft(MaidPersonaDraft newDraft) {
        return new MaidPersonaConfig(mode, newDraft);
    }

    /** 整份替换模式（内容不变） */
    public MaidPersonaConfig withMode(MaidPersonaMode newMode) {
        return new MaidPersonaConfig(newMode, draft);
    }

    /**
     * 从 AI 数据 tag 读取完整配置。
     * <p>
     * 模式字段缺失或未知值按原版处理（旧存档没有该字段，且四栏草稿可能已存在——此时升级后仍是原版模式，
     * 不自动切换到四栏）。
     */
    public static MaidPersonaConfig readFrom(CompoundTag tag) {
        if (tag == null) {
            return DEFAULT;
        }
        MaidPersonaMode mode = tag.contains(KEY_MODE, Tag.TAG_STRING)
                ? MaidPersonaMode.fromTag(tag.getString(KEY_MODE))
                : MaidPersonaMode.ORIGINAL;
        return new MaidPersonaConfig(mode, MaidPersonaDraft.readFrom(tag));
    }

    /**
     * 把完整配置写入 AI 数据 tag。
     * <p>
     * 模式字段与四栏标签分别写：
     * <ul>
     *   <li>四栏模式：写出模式字段（四栏为空也写，保证「已选择四栏模式」这一状态不丢）；</li>
     *   <li>原版模式：移除模式字段——默认值不落到存档里，避免给所有旧女仆的 NBT 平添写入；</li>
     *   <li>四栏内容按 {@link MaidPersonaDraft#writeTo} 的既有规则（严格空则移除标签）。</li>
     * </ul>
     */
    public void writeTo(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        if (isStructured()) {
            tag.putString(KEY_MODE, mode.tag());
        } else {
            tag.remove(KEY_MODE);
        }
        draft.writeTo(tag);
    }
}
