package com.maidmod.selftalk.persona;

import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.history.HistoryTokenizer;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 四栏人设中「背景 + 爱好」的 BM25 索引（不可变，纯文本，无 Minecraft 依赖）。
 * <p>
 * 与历史索引刻意分开：历史索引按对话块组织、需要跨轮次合并，人设索引只按<b>单个句子片段</b>组织，
 * 且只索引四栏中的背景与爱好这两栏。两者互不共享缓存、互不影响对方的检索结果。
 * <p>
 * 切片规则（固定，不开放配置）：
 * <ol>
 *   <li>只取背景与爱好两栏，身份、性格不参与（它们在每一轮被固定注入，不需要检索）；</li>
 *   <li>每栏先按换行分段，再对每段用 {@link BreakIterator}（{@link Locale#ROOT}）切句；</li>
 *   <li>空白片段丢弃，其余片段保留完整句子与标点，不做改写、不做长度截断；</li>
 *   <li>分类之间不合并：一个片段永远只属于背景或爱好；</li>
 *   <li>顺序号按「背景在前、爱好在后，各自保持原文顺序」统一编号。</li>
 * </ol>
 * <p>
 * 打分沿用历史检索的固定参数：{@code K1 = 1.2}、{@code B = 0.75}、正值 idf。
 * 统计量（N、df、平均长度）从<b>全部</b>片段算出，不只统计候选片段。
 * <p>
 * 候选闸门与输出规则：
 * <ul>
 *   <li>候选必须命中至少一个原始关键词（{@link HistoryTokenizer#matchesAnyKeyword}）且得分大于 0；</li>
 *   <li>单轮最多返回 {@value #MAX_RETRIEVED_FRAGMENTS} 个完整片段（背景与爱好<b>合计</b>上限）；</li>
 *   <li>同分时按原始顺序靠前者优先（结果确定）；</li>
 *   <li>输出顺序为「背景 → 爱好，各自按原文顺序」，与选出顺序无关；</li>
 *   <li>关键词为空、库为空、无命中一律返回空列表——<b>不</b>退化成「整份动态人设全量注入」。</li>
 * </ul>
 */
public final class PersonaRetrievalIndex {

    /** 单轮最多召回的人设片段数（背景与爱好合计） */
    public static final int MAX_RETRIEVED_FRAGMENTS = 3;

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** 与片段一一对应的文档记录（index 即 {@code fragments} 下标，也是同分时的顺序依据） */
    private record Doc(int index, Map<String, Integer> termFrequency, int length) {
    }

    private final List<PersonaFragment> fragments;
    private final List<Doc> docs;
    private final Map<String, Integer> documentFrequency;
    private final double averageLength;

    private PersonaRetrievalIndex(List<PersonaFragment> fragments, List<Doc> docs,
                                  Map<String, Integer> documentFrequency, double averageLength) {
        this.fragments = fragments;
        this.docs = docs;
        this.documentFrequency = documentFrequency;
        this.averageLength = averageLength;
    }

    /** 空索引（两栏都为空、或全被空白片段过滤掉时的取值） */
    public static PersonaRetrievalIndex empty() {
        return new PersonaRetrievalIndex(List.of(), List.of(), Map.of(), 0.0);
    }

    /** 用一份四栏内容构建索引（只取背景与爱好） */
    public static PersonaRetrievalIndex build(MaidPersonaDraft draft) {
        if (draft == null) {
            return empty();
        }
        return build(draft.background(), draft.hobbies());
    }

    /** 用背景与爱好原文构建索引 */
    public static PersonaRetrievalIndex build(String background, String hobbies) {
        List<PersonaFragment> all = new ArrayList<>();
        collect(all, PersonaFragment.Category.BACKGROUND, background);
        collect(all, PersonaFragment.Category.HOBBIES, hobbies);
        if (all.isEmpty()) {
            return empty();
        }
        List<PersonaFragment> copy = List.copyOf(all);
        List<Doc> docs = new ArrayList<>(copy.size());
        Map<String, Integer> df = new HashMap<>();
        long totalLength = 0;
        for (int i = 0; i < copy.size(); i++) {
            List<String> tokens = HistoryTokenizer.tokenizeSequence(copy.get(i).text());
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
        return new PersonaRetrievalIndex(copy, List.copyOf(docs), Map.copyOf(df),
                (double) totalLength / copy.size());
    }

    /**
     * 把一栏原文切成片段并追加到 {@code out}。
     * <p>
     * 先按换行分段（玩家写的换行是有意义的自然断点），再在段内用 {@link BreakIterator} 切句。
     * 段内切不出句子时整段作为一个片段，不会丢内容。
     */
    private static void collect(List<PersonaFragment> out, PersonaFragment.Category category, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
            iterator.setText(line);
            int start = iterator.first();
            boolean any = false;
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                String sentence = line.substring(start, end).trim();
                if (sentence.isEmpty()) {
                    continue;
                }
                any = true;
                out.add(new PersonaFragment(category, sentence, out.size()));
            }
            if (!any) {
                // 兜底：切句器未给出任何边界时整段保留，绝不丢内容
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    out.add(new PersonaFragment(category, trimmed, out.size()));
                }
            }
        }
    }

    /** 可召回片段数（N） */
    public int fragmentCount() {
        return fragments.size();
    }

    /** 索引是否为空（空库直接跳过检索，不发无意义的规划请求） */
    public boolean isEmpty() {
        return fragments.isEmpty();
    }

    /**
     * 检索：候选闸门（至少命中一个原始关键词）→ BM25 打分 → 取前 {@value #MAX_RETRIEVED_FRAGMENTS} 个
     * → 按原始顺序（背景在前、爱好在后，各自保持原文顺序）输出。
     * <p>
     * 空关键词、空库、平均长度为零都直接返回空列表，不做除零与无意义打分。
     */
    public List<PersonaFragment> search(List<String> keywords) {
        if (keywords == null || keywords.isEmpty() || fragments.isEmpty() || averageLength <= 0.0) {
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
        // 查询词项去重：重复关键词不得重复放大得分
        Set<String> queryTerms = new LinkedHashSet<>(
                HistoryTokenizer.tokenize(String.join(" ", normalized)));

        List<Scored> scored = new ArrayList<>();
        for (Doc doc : docs) {
            String docNormalized = HistoryTokenizer.normalize(fragments.get(doc.index()).text());
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
        // 分数降序；同分时原始顺序靠前者优先（index 即顺序号，越小越靠前）
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingInt(Scored::index));
        List<PersonaFragment> top = new ArrayList<>(MAX_RETRIEVED_FRAGMENTS);
        for (int i = 0; i < scored.size() && i < MAX_RETRIEVED_FRAGMENTS; i++) {
            top.add(fragments.get(scored.get(i).index()));
        }
        // 选出后再按原始顺序排列输出：背景 → 爱好，各自保持原文顺序
        top.sort(Comparator.comparingInt(PersonaFragment::order));
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
