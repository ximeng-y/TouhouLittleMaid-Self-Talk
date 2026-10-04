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
 *   <li>非汉字的字母／数字连续段（含 CJK 假名、韩文音节）整体按一个词项处理；</li>
 *   <li>词项去重，重复关键词不重复放大得分。</li>
 * </ul>
 * 另提供「原始关键词形态」用于 OR 候选筛选：中文/假名关键词按子串包含匹配，
 * 纯 ASCII 字母数字关键词按词边界匹配——避免 {@code hp} 命中 {@code alpha} 这类短词嵌入。
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
        String normalized = normalize(text);
        Set<String> tokens = new LinkedHashSet<>();
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
            } else if (isWordChar(c)) {
                int end = i;
                while (end < length && isWordChar(normalized.charAt(end))) {
                    end++;
                }
                tokens.add(normalized.substring(i, end));
                i = end;
            } else {
                i++;
            }
        }
        return new ArrayList<>(tokens);
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
     * 单个关键词命中判定：纯 ASCII 字母数字关键词按词边界，
     * 其余（中文、假名、混合形态）按子串包含。
     */
    private static boolean containsKeyword(String normalizedDoc, String keyword) {
        if (isAsciiWord(keyword)) {
            int from = 0;
            while (true) {
                int at = normalizedDoc.indexOf(keyword, from);
                if (at < 0) {
                    return false;
                }
                boolean leftOk = at == 0 || !isWordChar(normalizedDoc.charAt(at - 1));
                int after = at + keyword.length();
                boolean rightOk = after >= normalizedDoc.length() || !isWordChar(normalizedDoc.charAt(after));
                if (leftOk && rightOk) {
                    return true;
                }
                from = at + 1;
            }
        }
        return normalizedDoc.contains(keyword);
    }

    /** 纯 ASCII 字母数字（词边界匹配的适用前提） */
    private static boolean isAsciiWord(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    /** 汉字（含扩展 B 区，代理对按高位字符判定即可满足相邻双字需求） */
    private static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /**
     * 词字符：字母（含日文假名、韩文音节）、数字、下划线。
     * 汉字单独成支，不在此列。
     */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
