package com.maidmod.selftalk.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 玩家对话块的 BM25 索引（不可变，纯文本，无 Minecraft 依赖）。
 * <p>
 * 固定内部参数（不开放配置）：{@code K1 = 1.2}、{@code B = 0.75}、
 * 最多返回 {@value #MAX_RETRIEVED_BLOCKS} 个完整块、关键词上限 {@value #MAX_KEYWORDS}。
 * 统计量（N、df、平均长度）一律从<b>完整</b>可检索块集合算出，不只统计候选块——
 * 只用候选集算 df 会让「候选只剩一两块」时 idf 失真。
 * <p>
 * 打分公式（标准 BM25，带正值 idf）：
 * <pre>
 * idf(t)    = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
 * score(d,q) = Σ idf(t) × tf(t,d)×(K1+1) / [tf(t,d) + K1×(1 - B + B×len(d)/avgLen)]
 * </pre>
 * 同一分数下以历史顺序<b>较新</b>的块优先，保证结果确定；选出最多三个后，
 * 再按从旧到新排列输出（模型读到的时间线仍是正向的）。
 */
public final class HistoryRetrievalIndex {

    /** 关键词上限（协议与规划提示词同样按此数收束） */
    public static final int MAX_KEYWORDS = 6;
    /** 单次最多召回的完整对话块数 */
    public static final int MAX_RETRIEVED_BLOCKS = 3;

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** 与块一一对应的文档记录（index 即 {@code blocks} 下标，也用于同分时的「较新优先」比较） */
    private record Doc(int index, Map<String, Integer> termFrequency, int length) {
    }

    private final List<DialogueBlock> blocks;
    private final List<Doc> docs;
    private final Map<String, Integer> documentFrequency;
    private final double averageLength;

    private HistoryRetrievalIndex(List<DialogueBlock> blocks, List<Doc> docs,
                                  Map<String, Integer> documentFrequency, double averageLength) {
        this.blocks = blocks;
        this.docs = docs;
        this.documentFrequency = documentFrequency;
        this.averageLength = averageLength;
    }

    /** 空索引（历史为空、全部被来源过滤掉时的取值） */
    public static HistoryRetrievalIndex empty() {
        return new HistoryRetrievalIndex(List.of(), List.of(), Map.of(), 0.0);
    }

    /**
     * 用一组对话块构建索引。
     * <p>
     * 文档长度按<b>词项总数</b>计（不是字符串长度）：中文单字与双字词项都会被计入，
     * 与 tf 的口径一致，长度归一化才有意义。
     */
    public static HistoryRetrievalIndex build(List<DialogueBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return empty();
        }
        List<DialogueBlock> copy = List.copyOf(blocks);
        List<Doc> docs = new ArrayList<>(copy.size());
        Map<String, Integer> df = new HashMap<>();
        long totalLength = 0;
        for (int i = 0; i < copy.size(); i++) {
            List<String> tokens = HistoryTokenizer.tokenize(copy.get(i).text());
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            docs.add(new Doc(i, tf, tokens.size()));
            totalLength += tokens.size();
            for (String term : tf.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }
        return new HistoryRetrievalIndex(copy, List.copyOf(docs), Map.copyOf(df),
                (double) totalLength / copy.size());
    }

    /** 可检索块数（N） */
    public int blockCount() {
        return blocks.size();
    }

    /**
     * 检索：候选闸门（至少命中一个原始关键词）→ BM25 打分 → 取前三个 → 按历史顺序（从旧到新）输出。
     * <p>
     * 空关键词、空库、平均长度为零都直接返回空列表，不做除零与无意义打分。
     */
    public List<DialogueBlock> search(List<String> keywords) {
        if (keywords == null || keywords.isEmpty() || blocks.isEmpty() || averageLength <= 0.0) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(keywords.size());
        for (String keyword : keywords) {
            String value = HistoryTokenizer.normalize(keyword);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        // 查询词项去重：重复关键词不得重复放大得分（同一词项只累加一次 idf×tf 项）
        Set<String> queryTerms = new java.util.LinkedHashSet<>(HistoryTokenizer.tokenize(String.join(" ", normalized)));

        List<Scored> scored = new ArrayList<>();
        for (Doc doc : docs) {
            String docNormalized = HistoryTokenizer.normalize(blocks.get(doc.index()).text());
            if (!HistoryTokenizer.matchesAnyKeyword(normalized, docNormalized)) {
                continue;
            }
            double score = score(doc, queryTerms);
            if (score > 0.0) {
                scored.add(new Scored(doc.index(), score));
            }
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        // 分数降序；同分时以下标较大（历史顺序较新）者优先
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Comparator.comparingInt(Scored::index).reversed()));
        List<DialogueBlock> top = new ArrayList<>(MAX_RETRIEVED_BLOCKS);
        for (int i = 0; i < scored.size() && i < MAX_RETRIEVED_BLOCKS; i++) {
            top.add(blocks.get(scored.get(i).index()));
        }
        // 选出后再按历史顺序（从旧到新）排列输出
        top.sort(Comparator.comparingInt(DialogueBlock::startIndex));
        return List.copyOf(top);
    }

    private record Scored(int index, double score) {
    }

    private double score(Doc doc, Set<String> queryTerms) {
        double total = 0.0;
        int n = docs.size();
        for (String term : queryTerms) {
            Integer tf = doc.termFrequency().get(term);
            if (tf == null || tf <= 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            double denominator = tf + K1 * (1.0 - B + B * doc.length() / averageLength);
            total += idf * (tf * (K1 + 1.0)) / denominator;
        }
        return total;
    }
}
