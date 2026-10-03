package com.maidmod.selftalk;

import com.maidmod.selftalk.history.DialogueBlock;
import com.maidmod.selftalk.history.HistoryMessage;
import com.maidmod.selftalk.history.HistoryRetrievalIndex;
import com.maidmod.selftalk.history.HistoryTokenizer;
import com.maidmod.selftalk.history.KeywordPlanParser;
import com.maidmod.selftalk.history.PlayerDialogueLibrary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 历史上下文模式纯文本内核的自检入口（不依赖 JUnit，也不依赖 Minecraft／TLM 类型）。
 * <p>
 * 覆盖计划 §8.2 中「分块与来源」「BM25」「JSON 与重试」三组检查——
 * 这几组的实现全部落在 {@code history} 包与模式枚举上，可用 javac+java 直接运行。
 * <p>
 * 运行（工作根目录，JDK 17 或 21 均可；gson 用 TLM 依赖树里的 2.10.1）：
 * <pre>
 * javac -encoding UTF-8 -d .XMTEMP/issue33-out/classes -cp &lt;gson.jar&gt; src/test/java/com/maidmod/selftalk/HistoryCoreTest.java src/main/java/com/maidmod/selftalk/HistoryContextMode.java src/main/java/com/maidmod/selftalk/history/*.java
 * java -cp ... HistoryCoreTest
 * </pre>
 * Gradle 的 {@code test} 任务不会自动运行本入口，必须显式执行。
 */
public final class HistoryCoreTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testModeEnum();
        testFilterAndBlocks();
        testTokenizer();
        testBm25();
        testKeywordJson();
        System.out.printf("%n=== %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ===== 模式枚举 =====

    private static void testModeEnum() {
        check("默认模式为 full", HistoryContextMode.FULL.isDefault());
        check("full 存 id 为 full", "full".equals(HistoryContextMode.FULL.id()));
        check("循环 full→compact", HistoryContextMode.FULL.next() == HistoryContextMode.COMPACT);
        check("循环 compact→retrieval", HistoryContextMode.COMPACT.next() == HistoryContextMode.RETRIEVAL);
        check("循环 retrieval→full", HistoryContextMode.RETRIEVAL.next() == HistoryContextMode.FULL);
        check("fromId 认 full", HistoryContextMode.fromId("full") == HistoryContextMode.FULL);
        check("fromId 认 compact", HistoryContextMode.fromId("compact") == HistoryContextMode.COMPACT);
        check("fromId 认 retrieval", HistoryContextMode.fromId("retrieval") == HistoryContextMode.RETRIEVAL);
        check("非法 id 返回 null", HistoryContextMode.fromId("bogus") == null);
        check("null id 返回 null", HistoryContextMode.fromId(null) == null);
        check("非默认模式不止一个", !HistoryContextMode.COMPACT.isDefault()
                && !HistoryContextMode.RETRIEVAL.isDefault());
    }

    // ===== 来源过滤与分块 =====

    private static void testFilterAndBlocks() {
        HistoryMessage system = msg("SYSTEM", "人设", 0);
        HistoryMessage summary = msg("SYSTEM", "摘要", 0);
        HistoryMessage user1 = msg("USER", "我们去下界吧", 1);
        HistoryMessage assistant1 = msg("ASSISTANT", "好呀", 2);
        HistoryMessage selfTalk = msg("ASSISTANT", "自言自语一句", 3);
        HistoryMessage toolCall = msg("ASSISTANT", null, 4, true);
        HistoryMessage toolResult = msg("TOOL", "工具结果", 5);
        HistoryMessage user2 = msg("USER", "继续", 6);
        HistoryMessage assistant2 = msg("ASSISTANT", "嗯", 7);

        List<HistoryMessage> view = List.of(system, summary, user1, assistant1, selfTalk,
                toolCall, toolResult, user2, assistant2);
        String selfFingerprint = selfTalk.fingerprint();

        List<HistoryMessage> searchable =
                PlayerDialogueLibrary.filterSearchable(view, java.util.Set.of(selfFingerprint), java.util.Set.of());
        check("SYSTEM 不入库", searchable.stream().noneMatch(m -> m.isSystem()));
        check("TOOL 不入库", searchable.stream().noneMatch(m -> m.isTool()));
        check("带 tool_calls 的 assistant 不入库", searchable.stream().noneMatch(HistoryMessage::hasToolCalls));
        check("自话不入库", searchable.stream().noneMatch(m -> m.fingerprint().equals(selfFingerprint)));
        check("玩家消息入库", searchable.contains(user1) && searchable.contains(user2));
        check("普通回复入库", searchable.contains(assistant1) && searchable.contains(assistant2));
        check("样本条数正确", searchable.size() == 4);

        List<DialogueBlock> blocks = PlayerDialogueLibrary.buildBlocks(searchable);
        check("玩家问答各成一个块", blocks.size() == 2);
        check("首块起于玩家消息", blocks.get(0).startIndex() == 0);
        check("首块含两轮", blocks.get(0).messages().size() == 2);
        check("次块起于第二条玩家消息", blocks.get(1).startIndex() == 2);
        check("块内保持原顺序", blocks.get(1).messages().get(0).text().equals("继续"));

        // 连续两条 USER：各自成块，前块只有玩家消息也可以保留
        List<DialogueBlock> consecutive = PlayerDialogueLibrary.buildBlocks(
                List.of(msg("USER", "第一句", 1), msg("USER", "第二句", 2)));
        check("连续 USER 各自成块", consecutive.size() == 2);
        check("只有玩家的块可保留", consecutive.get(0).messages().size() == 1);

        // 孤立 assistant（没有前导玩家消息）不独立入库
        List<DialogueBlock> orphan = PlayerDialogueLibrary.buildBlocks(List.of(msg("ASSISTANT", "凭空一句", 1)));
        check("孤立 assistant 不成块", orphan.isEmpty());

        // 已知自话跳过但不切断块：块内其余消息保持原顺序
        HistoryMessage talk = msg("ASSISTANT", "自话", 3);
        List<HistoryMessage> mixed = PlayerDialogueLibrary.filterSearchable(
                List.of(user1, talk, assistant1), java.util.Set.of(talk.fingerprint()), java.util.Set.of());
        check("自话跳过不切断块", PlayerDialogueLibrary.buildBlocks(mixed).size() == 1);

        // 旧历史（无来源标记）按兼容规则保留
        HistoryMessage legacyUser = msg("USER", "老对话", 10);
        HistoryMessage legacyAssistant = msg("ASSISTANT", "老回复", 11);
        List<HistoryMessage> legacy = PlayerDialogueLibrary.filterSearchable(
                List.of(legacyUser, legacyAssistant), java.util.Set.of(), java.util.Set.of("死指纹"));
        check("旧来源未知回复按兼容规则保留", legacy.size() == 2);

        // 长块完整返回，无字符截断
        HistoryMessage longMsg = msg("USER", "长".repeat(5000), 20);
        List<HistoryMessage> longSearchable = PlayerDialogueLibrary.filterSearchable(
                List.of(longMsg, msg("ASSISTANT", "回", 21)), java.util.Set.of(), java.util.Set.of());
        List<DialogueBlock> longBlocks = PlayerDialogueLibrary.buildBlocks(longSearchable);
        String longText = longBlocks.get(0).text();
        check("长块完整保留（无截断）", longText.length() == "长".repeat(5000).length() + 1 + "回".length());
        check("长块内容以原文开头", longText.startsWith("长"));
    }

    // ===== 分词 =====

    private static void testTokenizer() {
        List<String> tokens = HistoryTokenizer.tokenize("下界恶魂");
        check("中文单字入库", tokens.contains("下") && tokens.contains("界"));
        check("中文相邻双字入库", tokens.contains("下界") && tokens.contains("恶魂"));
        check("中文相邻双字按相邻对生成", tokens.contains("界恶"));
        check("中文不产三字项", !tokens.contains("下界恶"));

        List<String> crossed = HistoryTokenizer.tokenize("你好，世界");
        check("标点不跨接双字", !crossed.contains("好世"));

        List<String> english = HistoryTokenizer.tokenize("Kill the Ghast");
        check("英文按词切分并小写", english.contains("kill") && english.contains("ghast"));
        check("英文停用词也保留为词项", english.contains("the"));

        // 去重：重复词项只出现一次
        List<String> dup = HistoryTokenizer.tokenize("下界下界");
        LinkedHashSet<String> unique = new LinkedHashSet<>(HistoryTokenizer.tokenize("下界下界"));
        check("查询分词去重", dup.size() == unique.size());

        check("NFKC 规范化（全角转半角）",
                HistoryTokenizer.normalize("ＡＢＣ").equals("abc"));

        // 英文关键词按词边界匹配：短词不得命中长单词的一部分
        check("英文短词不命中长单词内部",
                !HistoryTokenizer.matchesAnyKeyword(List.of("ill"), HistoryTokenizer.normalize("kill the ghast")));
        check("英文整词命中",
                HistoryTokenizer.matchesAnyKeyword(List.of("kill"), HistoryTokenizer.normalize("kill the ghast")));
        check("中文短语用包含匹配",
                HistoryTokenizer.matchesAnyKeyword(List.of("下界"), HistoryTokenizer.normalize("我在下界挖矿")));
    }

    // ===== BM25 =====

    private static void testBm25() {
        List<HistoryMessage> docs = List.of(
                msg("USER", "我们在下界建了基地", 1),
                msg("ASSISTANT", "好呀，下界很危险", 2),
                msg("USER", "今天天气不错", 3),
                msg("ASSISTANT", "是呢", 4),
                msg("USER", "下界恶魂又来了", 5),
                msg("ASSISTANT", "小心点", 6),
                msg("USER", "我种了一片小麦", 7),
                msg("ASSISTANT", "会好好长的", 8));
        HistoryRetrievalIndex index = HistoryRetrievalIndex.build(PlayerDialogueLibrary.buildBlocks(docs));
        check("块数正确", index.blockCount() == 4);

        List<DialogueBlock> hits = index.search(List.of("恶魂"));
        check("稀有词命中唯一块", hits.size() == 1);
        check("命中块为含关键词的那一轮", hits.get(0).text().contains("恶魂"));

        List<DialogueBlock> common = index.search(List.of("下界"));
        check("高频词最多返回三块", common.size() <= HistoryRetrievalIndex.MAX_RETRIEVED_BLOCKS);
        check("高频词命中两块", common.size() == 2);
        check("输出按原历史顺序（旧在前）",
                common.get(0).startIndex() < common.get(1).startIndex());

        // 同分稳定：两块同样只命中一次同一词，靠「较新的优先」决定取舍后仍按历史顺序输出
        List<HistoryMessage> tie = List.of(
                msg("USER", "苹果", 1), msg("ASSISTANT", "好", 2),
                msg("USER", "苹果", 3), msg("ASSISTANT", "行", 4));
        List<DialogueBlock> tieHits = HistoryRetrievalIndex.build(PlayerDialogueLibrary.buildBlocks(tie))
                .search(List.of("苹果"));
        check("同分命中两块的输出顺序确定（旧在前）",
                tieHits.size() == 2 && tieHits.get(0).startIndex() < tieHits.get(1).startIndex());

        check("零命中返回空", index.search(List.of("不存在的地名")).isEmpty());
        check("空关键词返回空", index.search(List.of()).isEmpty());
        check("空索引返回空", HistoryRetrievalIndex.build(List.of()).search(List.of("下界")).isEmpty());

        // 候选门槛：只有汉字碎片重合（未命中原始关键词）不进入候选
        check("无原始关键词命中则不召回", index.search(List.of("魂恶")).isEmpty());

        // 使用完整语料统计而非候选集：文档频率越高，idf 越小
        List<DialogueBlock> rare = index.search(List.of("恶魂"));
        check("稀有词可召回（正 idf）", !rare.isEmpty());

        // 关键词数量上限
        List<String> many = new ArrayList<>();
        for (String token : HistoryTokenizer.tokenize("下界恶魂基地小麦天气")) {
            many.add(token);
        }
        check("关键词上限存在", HistoryRetrievalIndex.MAX_KEYWORDS == 6);
        check("召回块上限存在", HistoryRetrievalIndex.MAX_RETRIEVED_BLOCKS == 3);
    }

    // ===== 关键词 JSON 与纠正 =====

    private static void testKeywordJson() {
        KeywordPlanParser.Result ok = KeywordPlanParser.parse("{\"keywords\":[\"下界\",\"恶魂\"]}");
        check("正常 JSON 解析成功", ok.error() == null);
        check("正常 JSON 关键词正确", ok.keywords().equals(List.of("下界", "恶魂")));

        KeywordPlanParser.Result fenced =
                KeywordPlanParser.parse("```json\n{\"keywords\":[\"下界\"]}\n```");
        check("一层代码围栏可剥除", fenced.error() == null && fenced.keywords().equals(List.of("下界")));

        KeywordPlanParser.Result empty = KeywordPlanParser.parse("{\"keywords\":[]}");
        check("空数组合法", empty.error() == null && empty.keywords().isEmpty());

        KeywordPlanParser.Result over = KeywordPlanParser.parse(
                "{\"keywords\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]}");
        check("超六词截取而非报错",
                over.error() == null && over.keywords().size() == HistoryRetrievalIndex.MAX_KEYWORDS);

        KeywordPlanParser.Result dup = KeywordPlanParser.parse("{\"keywords\":[\"下界\",\" 下界 \"]}");
        check("去首尾空白并忽略大小写去重",
                dup.error() == null && dup.keywords().equals(List.of("下界")));

        KeywordPlanParser.Result blank = KeywordPlanParser.parse("{\"keywords\":[\"\",\"  \",\"下界\"]}");
        check("空项被剔除", blank.error() == null && blank.keywords().equals(List.of("下界")));

        check("缺字段被拒", KeywordPlanParser.parse("{}").error() != null);
        check("错误类型被拒", KeywordPlanParser.parse("{\"keywords\":\"下界\"}").error() != null);
        check("非字符串项被拒", KeywordPlanParser.parse("{\"keywords\":[1,2]}").error() != null);
        check("截断 JSON 被拒", KeywordPlanParser.parse("{\"keywords\":[\"下界\"").error() != null);
        check("尾随文字被拒", KeywordPlanParser.parse("{\"keywords\":[\"下界\"]} 就这样").error() != null);
        check("第二个 JSON 被拒",
                KeywordPlanParser.parse("{\"keywords\":[\"下界\"]}{\"keywords\":[\"恶魂\"]}").error() != null);
        check("单引号不被接受", KeywordPlanParser.parse("{'keywords':['下界']}").error() != null);
        check("尾随逗号不被接受", KeywordPlanParser.parse("{\"keywords\":[\"下界\",]}").error() != null);
        check("重复字段被拒", KeywordPlanParser.parse(
                "{\"keywords\":[\"下界\"],\"keywords\":[\"恶魂\"]}").error() != null);
        check("无关额外字段可忽略", KeywordPlanParser.parse(
                "{\"note\":\"x\",\"keywords\":[\"下界\"]}").error() == null);
        check("裸字符串被拒", KeywordPlanParser.parse("\"下界\"").error() != null);
        check("数组顶层被拒", KeywordPlanParser.parse("[\"下界\"]").error() != null);
        check("空输入被拒", KeywordPlanParser.parse("").error() != null);
        check("null 输入被拒", KeywordPlanParser.parse(null).error() != null);
    }

    // ===== 工具方法 =====

    private static HistoryMessage msg(String role, String text, long gameTime) {
        return msg(role, text, gameTime, false);
    }

    private static HistoryMessage msg(String role, String text, long gameTime, boolean toolCalls) {
        return new HistoryMessage(role, text, gameTime, toolCalls);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
