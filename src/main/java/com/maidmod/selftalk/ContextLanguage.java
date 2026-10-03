package com.maidmod.selftalk;

import java.util.Locale;

/**
 * 背景模板语言：只有中文与英文两种。
 * <p>
 * 纯 Java，不依赖 Minecraft / TLM 类型，可脱离游戏自检。
 * <p>
 * 选择口径（issue #23 锁定，不得自行扩展）：
 * <ol>
 *   <li>先经 {@link SelfTalkContexts#sanitizeLanguage} 做既有语言标签校验——非法或缺失的标签
 *       在这里已经被换成了配置默认语言（zh_cn），本类不再另建一套回退规则；</li>
 *   <li>主语言为 {@code zh} 时选中文，涵盖 {@code zh}、{@code zh_cn}、{@code zh_tw}、{@code zh_hk}
 *       等全部合法中文标签；</li>
 *   <li>其余合法语言（{@code en_us}、{@code ja_jp} 等）统一选英文。</li>
 * </ol>
 * 语言只决定背景事实的表达方式，<b>不</b>改变模型输出语言指令：日语聊天可以用英文背景，
 * 但仍要求模型用日语回答（见 {@link SelfTalkContexts#languageInstruction}）。
 */
public enum ContextLanguage {

    ZH,
    EN;

    /**
     * 由<b>已校验</b>的语言标签选择模板语言。
     * <p>
     * 入参必须是 {@link SelfTalkContexts#sanitizeLanguage} 的结果（游戏侧一律用
     * {@link SelfTalkContexts#languageOf} 走统一入口）；直接传原始标签会让非法值落到英文，
     * 与「非法或缺失沿用现有默认语言」的口径不符。
     * <p>
     * 大小写不敏感：{@code sanitizeLanguage} 的形态校验允许大小写且原样返回，合法标签
     * {@code ZH_CN} / {@code Zh_TW} 同样要落到中文——输出语言指令经 {@code Locale} 识别为中文，
     * 若这里区分大小写就会出现「要求用中文说话、背景却是英文」的分裂。
     */
    public static ContextLanguage of(String sanitizedLanguage) {
        return sanitizedLanguage != null
                && sanitizedLanguage.toLowerCase(Locale.ROOT).startsWith("zh") ? ZH : EN;
    }

    public boolean isZh() {
        return this == ZH;
    }
}
