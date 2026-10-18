package com.maidmod.selftalk.history;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索分词与匹配（纯文本，无 Minecraft 依赖）。
 * <p>
 * 规则：
 * <ul>
 *   <li>统一执行 Unicode 规范化（NFKC）与 {@link Locale#ROOT} 小写归一化，<b>仅用于匹配</b>，
 *       绝不改写最终输出的原文；</li>
 *   <li>连续汉字生成单字项与相邻双字项（不跨标点或空白拼接）；</li>
 *   <li>ASCII 字母／数字／下划线词段整体按一个词项处理，<b>遇到汉字必须断开</b>
 *       （{@code hp上限} 拆成 {@code hp} 与汉字项，而不是把 {@code hp上限} 当成一个词）；</li>
 *   <li>其他非汉字 Unicode 字母数字连续段（假名、韩文音节等）整体按一个词项处理；</li>
 *   <li>查询分词去重，重复关键词不重复放大得分；文档侧需要真实词频与文档长度，
 *       因此另提供保留重复项的 {@link #tokenizeSequence(String)}。</li>
 * </ul>
 * 另提供「原始关键词形态」用于 OR 候选筛选：中文/假名关键词按子串包含匹配，
 * 纯 ASCII 字母数字下划线关键词按词边界匹配——词边界只把 ASCII 字母、数字与下划线
 * 视为同一词段的字符，因此 {@code hp} 可以命中 {@code hp上限}，但不会命中 {@code alphabet}
 * 内部的子串。
 */
public final class HistoryTokenizer {

    private HistoryTokenizer() {
    }

    /** 归一化（NFKC + Locale.ROOT 小写），仅用于匹配 */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /** 查询与文档共用的分词结果（去重，保持出现顺序） */
    public static List<String> tokenize(String text) {
        return new ArrayList<>(new LinkedHashSet<>(tokenizeSequence(text)));
    }

    /**
     * 按出现顺序保留重复项的词项序列。
     * <p>
     * 文档索引的 {@code tf} 与文档长度必须基于这个序列统计：去重后的列表会让每个词项的频次恒为 1，
     * BM25 的饱和项与长度归一化都会失去意义。查询侧仍使用去重后的 {@link #tokenize(String)}。
     */
    public static List<String> tokenizeSequence(String text) {
        String normalized = normalize(text);
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int length = normalized.length();
        while (i < length) {
            char c = normalized.charAt(i);
            if (isHan(c)) {
                int end = i;
                while (end < length && isHan(normalized.charAt(end))) {
                    end++;
                }
                for (int k = i; k < end; k++) {
                    tokens.add(String.valueOf(normalized.charAt(k)));
                }
                for (int k = i; k + 1 < end; k++) {
                    tokens.add(normalized.substring(k, k + 2));
                }
                i = end;
            } else if (isAsciiWordChar(c)) {
                // 只消费 ASCII 词段：紧随其后的汉字属于汉字分支，不能并入同一词项
                int end = i;
                while (end < length && isAsciiWordChar(normalized.charAt(end))) {
                    end++;
                }
                tokens.add(normalized.substring(i, end));
                i = end;
            } else if (isOtherWordChar(c)) {
                int end = i;
                while (end < length && isOtherWordChar(normalized.charAt(end))) {
                    end++;
                }
                tokens.add(normalized.substring(i, end));
                i = end;
            } else {
                i++;
            }
        }
        return tokens;
    }

    /**
     * 文档是否命中至少一个原始关键词形态。
     * <p>
     * 这是 BM25 之前的候选闸门：只有汉字碎片重合、没有完整关键词命中的文档不进候选集合。
     * 关键词本身已由调用方归一化、去空、去重并截断到上限。
     */
    public static boolean matchesAnyKeyword(List<String> keywords, String normalizedDoc) {
        if (keywords == null || keywords.isEmpty() || normalizedDoc == null || normalizedDoc.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword.isEmpty()) {
                continue;
            }
            if (containsKeyword(normalizedDoc, keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单个关键词命中判定：纯 ASCII 字母数字下划线关键词按词边界，
     * 其余（中文、假名、混合形态）按子串包含。
     * <p>
     * 词边界只把 ASCII 字母、数字、下划线视为同一词段：汉字、假名等非 ASCII 字符
     * 天然构成边界，所以 {@code hp} 能命中 {@code hp上限}；而 {@code alphabet} 内部
     * 的 ASCII 字母仍会阻止 {@code hp} 命中。
     */
    private static boolean containsKeyword(String normalizedDoc, String keyword) {
        if (isAsciiWord(keyword)) {
            int from = 0;
            while (true) {
                int at = normalizedDoc.indexOf(keyword, from);
                if (at < 0) {
                    return false;
                }
                boolean leftOk = at == 0 || !isAsciiWordChar(normalizedDoc.charAt(at - 1));
                int after = at + keyword.length();
                boolean rightOk = after >= normalizedDoc.length() || !isAsciiWordChar(normalizedDoc.charAt(after));
                if (leftOk && rightOk) {
                    return true;
                }
                from = at + 1;
            }
        }
        return normalizedDoc.contains(keyword);
    }

    /** 纯 ASCII 字母数字下划线（词边界匹配的适用前提） */
    private static boolean isAsciiWord(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isAsciiWordChar(value.charAt(i))) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    /** ASCII 词段字符：小写字母、数字、下划线（归一化后只会出现小写） */
    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** 汉字（含扩展 B 区，代理对按高位字符判定即可满足相邻双字需求） */
    private static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /**
     * 汉字以外的其余字母数字（假名、韩文音节等）。
     * 汉字与 ASCII 词段各有独立分支，这里必须把它们排除，避免跨段拼接。
     */
    private static boolean isOtherWordChar(char c) {
        return !isHan(c) && !isAsciiWordChar(c) && Character.isLetterOrDigit(c);
    }
}
