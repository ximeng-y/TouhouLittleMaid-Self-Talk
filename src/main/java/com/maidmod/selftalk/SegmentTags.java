package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * 上下文段标签：请求构造时注入、发送前剥离（不落盘、不进界面）。
 * <p>
 * 设计约束（改动前必读）：
 * <ul>
 *   <li>标签在<b>请求内存列表</b>中按段边界注入（内容注入式，消息条数与角色不变），
 *       永不写入 TLM 历史（NBT MaidHistoryChat / 互聊窗口 / 玩家历史记录均无标签）；</li>
 *   <li>标签名采用高唯一性英文长串（<b>不是</b>玩家自然用语，普通对话不会出现），
 *       并配合提示词"不得输出任何标签/标记"声明；模型若仍输出标签，
 *       由 {@link #stripResponse} 在写历史/显示之前硬剥离——剥离是最终保证；</li>
 *   <li>剥离必须发生在任何持久化/展示之前（onSuccess HEAD 与 InterChatCallback.onSuccess 首行），
 *       漏剥一处即会污染 NBT 与聊天记录界面。</li>
 * </ul>
 */
public final class SegmentTags {

    /** 与主人聊天段开启标签 */
    public static final String OWNER_OPEN = "<maid-owner-chat>";
    /** 与主人聊天段关闭标签 */
    public static final String OWNER_CLOSE = "</maid-owner-chat>";
    /** 自话/互聊段开启标签 */
    public static final String SELF_OPEN = "<maid-self-chat>";
    /** 自话/互聊段关闭标签 */
    public static final String SELF_CLOSE = "</maid-self-chat>";

    // 自定义 Prompt 主人风格段（功能 A）与 Tool 策略段（功能 B）的标签。
    // 与 SelfTalkPrompts 提示词文本内嵌的标签保持一致，改动须同步。
    /** 主人风格段开启标签 */
    private static final String OWNER_STYLE_NOTE_OPEN = "<owner-style-note>";
    /** 主人风格段关闭标签 */
    private static final String OWNER_STYLE_NOTE_CLOSE = "</owner-style-note>";
    /** 全局段子标签 */
    private static final String ALL_MAIDS_OPEN = "<all-maids>";
    private static final String ALL_MAIDS_CLOSE = "</all-maids>";
    /** 单只段子标签 */
    private static final String THIS_MAID_OPEN = "<this-maid>";
    private static final String THIS_MAID_CLOSE = "</this-maid>";
    /** Tool 策略段标签 */
    private static final String TOOL_POLICY_OPEN = "<tool-policy>";
    private static final String TOOL_POLICY_CLOSE = "</tool-policy>";

    /** 全部已知标签（精确剥除集合）：旧段标签 + 自定义 Prompt/Tool 策略段标签 */
    private static final String[] KNOWN_TAGS = {OWNER_OPEN, OWNER_CLOSE, SELF_OPEN, SELF_CLOSE,
            OWNER_STYLE_NOTE_OPEN, OWNER_STYLE_NOTE_CLOSE,
            ALL_MAIDS_OPEN, ALL_MAIDS_CLOSE,
            THIS_MAID_OPEN, THIS_MAID_CLOSE,
            TOOL_POLICY_OPEN, TOOL_POLICY_CLOSE};

    /**
     * 宽正则兜底：模型可能输出带空白/换行变体的标签（如 {@code <maid-owner-chat >}），
     * 精确替换漏网时兜底剔除并留痕。仅匹配本 mod 的标签命名空间，避免误伤正文。
     */
    private static final Pattern VARIANT_REG = Pattern.compile(
            "<\\s*/?\\s*(maid-(owner|self)-chat|owner-style-note|all-maids|this-maid|tool-policy)\\s*>",
            Pattern.CASE_INSENSITIVE);

    /** 零宽字符（玩家可用其拼接出变体正则不命中的伪标签，多数 LLM tokenizer 会归一化还原） */
    private static final Pattern ZERO_WIDTH_REG = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");

    private SegmentTags() {
    }

    /**
     * 剥离文本中的已知段标签（精确替换 + 宽正则兜底）。
     * 幂等：无标签时原样返回。
     */
    public static String stripKnownTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String tag : KNOWN_TAGS) {
            result = StringUtils.replace(result, tag, StringUtils.EMPTY);
        }
        if (VARIANT_REG.matcher(result).find()) {
            result = VARIANT_REG.matcher(result).replaceAll("");
            MaidSelfTalkMod.LOGGER.warn("Segment tag variant found in LLM output and stripped: {}", text);
        }
        return result;
    }

    /**
     * 请求侧玩家输入清洗：静默剥除玩家原话中的本 mod 段标签（精确 + 变体，不留日志）。
     * 玩家消息会被主人段标签包裹进请求，原话自带标签可提前闭合主人段、伪造段边界，
     * 混淆模型的来源区分；仅清洗请求体，TLM 历史仍写原文（前端无感）。
     */
    public static String stripTagsFromPlayerInput(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String tag : KNOWN_TAGS) {
            result = StringUtils.replace(result, tag, StringUtils.EMPTY);
        }
        result = ZERO_WIDTH_REG.matcher(result).replaceAll("");
        return VARIANT_REG.matcher(result).replaceAll("");
    }

    /**
     * 原地剥离 {@link ResponseChat} 的两个 public 可变字段（chatText/ttsText）。
     * <p>
     * 调用点必须早于所有持久化/展示：LLMCallback.onSuccess HEAD（mixin）
     * 与 InterChatCallback.onSuccess 首行（该类不调 super，mixin 不进入其方法体）。
     */
    public static void stripResponse(ResponseChat responseChat) {
        if (responseChat == null) {
            return;
        }
        responseChat.chatText = stripKnownTags(responseChat.chatText);
        responseChat.ttsText = stripKnownTags(responseChat.ttsText);
    }
}
