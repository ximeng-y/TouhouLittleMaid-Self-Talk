package com.maidmod.selftalk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 结构化女仆人设草稿：身份／性格／背景／爱好四栏，按每只女仆独立保存。
 * <p>
 * 首版固定四类，不支持自定义分类；四栏合计上限 {@link #MAX_TOTAL_LENGTH} 字符（按
 * {@link String#length()} 计），该上限由本类统一定义，客户端（界面校验）与服务端（保存校验）
 * 共用同一份口径。
 * <p>
 * 本类只承载草稿文本，<b>不</b>参与任何聊天请求，也不与原版整段人设（{@code customSetting}）
 * 互相复制或拼接。文本一律原样保留：读写都不做 {@code trim}、截断或提示词清洗
 * （长度校验只用于保存请求，读档不得静默删减已存内容）。
 * <p>
 * 不可变值对象：任何一栏的修改都返回新的实例，调用方以「整份替换」的方式更新宿主，
 * 避免逐栏写入带来的中间态。
 */
public record MaidPersonaDraft(String identity, String personality, String background, String hobbies) {

    /** 四栏合计字符上限（客户端阻断保存、服务端拒绝写入用同一常量） */
    public static final int MAX_TOTAL_LENGTH = 4096;

    /** 固定分类数量：身份、性格、背景、爱好 */
    public static final int CATEGORY_COUNT = 4;

    /** 空草稿：四栏均为严格空字符串 */
    public static final MaidPersonaDraft EMPTY = new MaidPersonaDraft("", "", "", "");

    /** NBT 复合标签名（与 TLM 自有的 {@code MaidAIChat} 平级，挂在该女仆 AI 数据 tag 的根上） */
    public static final String TAG_NAME = "MaidSelfTalkPersona";
    public static final String KEY_VERSION = "Version";
    public static final String KEY_IDENTITY = "Identity";
    public static final String KEY_PERSONALITY = "Personality";
    public static final String KEY_BACKGROUND = "Background";
    public static final String KEY_HOBBIES = "Hobbies";
    /** 标签结构版本：首版固定 1 */
    public static final int TAG_VERSION = 1;

    /** null 一律按空串处理，草稿内不保留 null（网络与 NBT 两侧都不表达 null 语义） */
    public MaidPersonaDraft {
        identity = identity == null ? "" : identity;
        personality = personality == null ? "" : personality;
        background = background == null ? "" : background;
        hobbies = hobbies == null ? "" : hobbies;
    }

    /** 按分类下标取栏位文本（越界返回空串；下标含义见 {@link #CATEGORY_COUNT}） */
    public String get(int index) {
        return switch (index) {
            case 0 -> identity;
            case 1 -> personality;
            case 2 -> background;
            case 3 -> hobbies;
            default -> "";
        };
    }

    /** 按分类下标替换单个栏位，返回新实例（越界不修改，返回自身） */
    public MaidPersonaDraft with(int index, String value) {
        String normalized = value == null ? "" : value;
        return switch (index) {
            case 0 -> new MaidPersonaDraft(normalized, personality, background, hobbies);
            case 1 -> new MaidPersonaDraft(identity, normalized, background, hobbies);
            case 2 -> new MaidPersonaDraft(identity, personality, normalized, hobbies);
            case 3 -> new MaidPersonaDraft(identity, personality, background, normalized);
            default -> this;
        };
    }

    /** 四栏合计字符数（与上限同一口径） */
    public int totalLength() {
        return identity.length() + personality.length() + background.length() + hobbies.length();
    }

    /** 合计是否在上限内（保存请求的前置校验） */
    public boolean withinLimit() {
        return totalLength() <= MAX_TOTAL_LENGTH;
    }

    /**
     * 是否为严格空草稿：四栏都是长度 0 的字符串。
     * <p>
     * 刻意不用 {@code isBlank()}——纯空白是玩家有意输入的内容（草稿不参与聊天，无需按空白语义清理），
     * 按空白判定会把「只按了空格」的草稿当成空草稿删掉存档标签。
     */
    public boolean isEmpty() {
        return identity.isEmpty() && personality.isEmpty() && background.isEmpty() && hobbies.isEmpty();
    }

    /**
     * 从 AI 数据 tag 读草稿。标签不存在（含首次读档、清空后的存档）时返回 {@link #EMPTY}；
     * 标签存在但缺少某栏时该栏为空。调用方必须<b>整份替换</b>内存值，
     * 不能因标签缺失而保留上一次的内容。
     */
    public static MaidPersonaDraft readFrom(CompoundTag tag) {
        if (tag == null || !tag.contains(TAG_NAME, Tag.TAG_COMPOUND)) {
            return EMPTY;
        }
        CompoundTag persona = tag.getCompound(TAG_NAME);
        return new MaidPersonaDraft(
                persona.getString(KEY_IDENTITY),
                persona.getString(KEY_PERSONALITY),
                persona.getString(KEY_BACKGROUND),
                persona.getString(KEY_HOBBIES));
    }

    /**
     * 写草稿到 AI 数据 tag。
     * <p>
     * 四栏均为严格空字符串时移除标签（isEmpty 口径），其余情况按 {@link #TAG_VERSION} 写出完整结构。
     * 写入不做 trim、截断与清洗：已存在的内容原样保留。
     */
    public void writeTo(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        if (isEmpty()) {
            tag.remove(TAG_NAME);
            return;
        }
        CompoundTag persona = new CompoundTag();
        persona.putInt(KEY_VERSION, TAG_VERSION);
        persona.putString(KEY_IDENTITY, identity);
        persona.putString(KEY_PERSONALITY, personality);
        persona.putString(KEY_BACKGROUND, background);
        persona.putString(KEY_HOBBIES, hobbies);
        tag.put(TAG_NAME, persona);
    }
}
