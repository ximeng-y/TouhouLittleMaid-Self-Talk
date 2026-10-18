package com.maidmod.selftalk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 预制复用 YAML/Markdown 编解码与人类条目匹配自检（无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * 覆盖：机器条目编解码往返、正文分段五种情况、BOM/CRLF/空白、人类条目 AND 匹配、
 * 诊断告警（未知 key / 未知 H2 / 非标量 env / 机器条目严格校验）、相对路径派生 entryId 稳定性。
 * 已由 {@code gradle check} 真实执行，失败即构建失败。
 */
public final class PrebuiltEntryCodecTest {

    private static int checks = 0;

    private PrebuiltEntryCodecTest() {
    }

    public static void main(String[] args) {
        machineRoundTrip();
        bodySectionCases();
        encodingEdges();
        andMatchSemantics();
        diagnostics();
        derivedEntryIdStability();
        machineStrictValidation();
        System.out.println("PrebuiltEntryCodecTest passed: " + checks + " checks");
    }

    /** 1. 机器条目写出后解析：字段、env、Chat/TTS 文本保持一致；无 TTS 条目回退为 chatText */
    private static void machineRoundTrip() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("game_time", "09:31");
        env.put("weather", "Sunny");
        env.put("dimension", "Overworld");
        UUID id = UUID.randomUUID();
        SelfTalkReuseStore.PrebuiltEntry entry = new SelfTalkReuseStore.PrebuiltEntry(
                id, "26-10-18-09:31:02", env, "zh_cn", "主人早上好～\n今天也要一起加油哦。",
                "zh_cn", "主人早上好～今天也要一起加油哦。", SelfTalkReuseStore.Origin.MACHINE);
        PrebuiltEntryCodec.ReadResult read = PrebuiltEntryCodec.readMachine(
                PrebuiltEntryCodec.writeMachine(entry), "entry-" + id + ".md");
        check(read.entry() != null, "机器条目往返应解析成功");
        SelfTalkReuseStore.PrebuiltEntry parsed = read.entry();
        check(read.issues().isEmpty(), "往返不应产生告警，实际 " + read.issues());
        check(parsed.entryId.equals(id) && parsed.origin == SelfTalkReuseStore.Origin.MACHINE,
                "entryId 与来源应一致");
        check(parsed.createdAt.equals(entry.createdAt) && parsed.chatLanguage.equals("zh_cn"),
                "createdAt/chatLanguage 应一致");
        check(parsed.env.equals(env), "env 往返应一致，实际 " + parsed.env);
        check(parsed.chatText.equals(entry.chatText) && parsed.ttsText.equals(entry.ttsText)
                        && parsed.ttsLanguage.equals("zh_cn"),
                "正文两段往返应一致");
        // 无 TTS 条目：写出省略 TTS 段，读取回退为 chatText
        SelfTalkReuseStore.PrebuiltEntry noTts = new SelfTalkReuseStore.PrebuiltEntry(
                id, "26-10-18-09:31:02", Map.of(), "en_us", "Good morning!", null, null,
                SelfTalkReuseStore.Origin.MACHINE);
        PrebuiltEntryCodec.ReadResult read2 = PrebuiltEntryCodec.readMachine(
                PrebuiltEntryCodec.writeMachine(noTts), "entry-" + id + ".md");
        check(read2.entry() != null && read2.entry().ttsText.equals("Good morning!")
                        && read2.entry().ttsLanguage == null,
                "缺 TTS 段应回退 chatText 且 ttsLanguage 保持缺失");
        // 写出内容里标量统一双引号（防 YAML 隐式类型转换）
        String raw = PrebuiltEntryCodec.writeMachine(entry);
        check(raw.contains("createdAt: \"26-10-18-09:31:02\"") && raw.contains("game_time: \"09:31\""),
                "字符串标量应写成双引号");
    }

    /** 2. 正文分段五种情况（经人类条目宽松通道） */
    private static void bodySectionCases() {
        UUID any = UUID.nameUUIDFromBytes("x".getBytes());
        // 无任何已识别 H2：全文即 chatText，ttsText 回退
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readCustom("主人早上好～", any);
        check(r.entry() != null && r.entry().chatText.equals("主人早上好～")
                && r.entry().ttsText.equals("主人早上好～"), "无头全文应作为 chatText 并回退 TTS");
        // 只有 Chat
        r = PrebuiltEntryCodec.readCustom("---\n---\n\n## Chat\n\n你好。", any);
        check(r.entry() != null && r.entry().chatText.equals("你好。")
                && r.entry().ttsText.equals("你好。"), "只 Chat 段应回退 TTS");
        // 只有 TTS：TTS 段同时作为两种文本
        r = PrebuiltEntryCodec.readCustom("## TTS\n\n你好呀。", any);
        check(r.entry() != null && r.entry().chatText.equals("你好呀。")
                && r.entry().ttsText.equals("你好呀。"), "只 TTS 段应同时作为两段");
        // Chat + TTS
        r = PrebuiltEntryCodec.readCustom("## chat\n\n上屏。\n\n## TTS\n\n语音。", any);
        check(r.entry() != null && r.entry().chatText.equals("上屏。")
                && r.entry().ttsText.equals("语音。"), "标题大小写不敏感且两段各取各的");
        // 未识别 H2：该段整段忽略并告警，Chat 段照常
        r = PrebuiltEntryCodec.readCustom("## Note\n\n忽略。\n\n## Chat\n\n保留。", any);
        check(r.entry() != null && r.entry().chatText.equals("保留。"), "未知 H2 不影响 Chat 段");
        check(hasIssue(r.issues(), "未识别的二级标题"), "未知 H2 应产生告警");
        // 标题全是未识别：整篇正文即 chatText
        r = PrebuiltEntryCodec.readCustom("## Note\n\n全文正文。", any);
        check(r.entry() != null && r.entry().chatText.equals("## Note\n\n全文正文。"),
                "无已识别 H2 时全文应作为 chatText");
    }

    /** 3. BOM、CRLF、首尾空白与内部换行 */
    private static void encodingEdges() {
        UUID any = UUID.nameUUIDFromBytes("x".getBytes());
        String content = "\uFEFF---\r\nenv:\r\n  weather: \"Sunny\"\r\n---\r\n\r\n## Chat\r\n\r\n  天气不错。\r\n\r\n## TTS\r\n\r\n  天气不错呢。\r\n";
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readCustom(content, any);
        check(r.entry() != null && "Sunny".equals(r.entry().env.get("weather")),
                "BOM 与 CRLF 应被剥离，env 正常解析");
        check(r.entry() != null && r.entry().chatText.equals("天气不错。")
                && r.entry().ttsText.equals("天气不错呢。"), "首尾空白应剥除");
        String multiline = "第一行\n第二行";
        r = PrebuiltEntryCodec.readCustom("## Chat\n\n" + multiline + "\n", any);
        check(r.entry() != null && r.entry().chatText.equals(multiline), "内部换行应保留");
        // 旧 CR 单独换行
        r = PrebuiltEntryCodec.readCustom("## Chat\n\nA\rB", any);
        check(r.entry() != null && r.entry().chatText.equals("A\nB"), "孤立 CR 应统一为 LF");
    }

    /** 4. 人类条目 AND 匹配语义：空 env 恒通过、部分 key 全相等通过、任一不等/缺失不通过 */
    private static void andMatchSemantics() {
        Map<String, String> current = Map.of("weather", "Sunny", "game_time", "09:31", "dimension", "Overworld");
        check(SelfTalkReuseService.allWrittenKeysEqual(Map.of(), current), "空 env 应恒通过");
        check(SelfTalkReuseService.allWrittenKeysEqual(Map.of("weather", "Sunny"), current),
                "部分 key 全相等应通过");
        check(SelfTalkReuseService.allWrittenKeysEqual(
                        Map.of("weather", "Sunny", "game_time", "09:31"), current),
                "全部 key 相等应通过");
        check(!SelfTalkReuseService.allWrittenKeysEqual(Map.of("weather", "Raining"), current),
                "任一值不等不应通过");
        check(!SelfTalkReuseService.allWrittenKeysEqual(Map.of("biome", "minecraft:plains"), current),
                "快照中缺失的 key 不应通过");
        // 经完整解析链验证：人类条目 env 从 frontmatter 进入条目并参与 AND 判定
        UUID any = UUID.nameUUIDFromBytes("x".getBytes());
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readCustom(
                "---\nenv:\n  weather: \"Sunny\"\n---\n\n## Chat\n\n天气不错。", any);
        check(r.entry() != null && SelfTalkReuseService.allWrittenKeysEqual(r.entry().env, current),
                "解析后的人类条目应参与 AND 匹配");
        check(r.entry() != null && r.entry().chatLanguage == null, "缺 chatLanguage 应表示语言通配");
    }

    /** 5. 诊断告警：未知环境 key、未知 H2、非标量 env、frontmatter 解析失败 */
    private static void diagnostics() {
        UUID any = UUID.nameUUIDFromBytes("x".getBytes());
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readCustom(
                "---\nenv:\n  wether: \"Sunny\"\n---\n\n## Chat\n\n你好。", any);
        check(r.entry() != null && hasIssue(r.issues(), "未知环境 key"), "未知环境 key 应产生告警且条目可用");
        // 非标量 env 值：人类条目丢该 key 继续；机器条目整文件跳过
        r = PrebuiltEntryCodec.readCustom(
                "---\nenv:\n  weather: [Sunny]\n  game_time: \"09:31\"\n---\n\n## Chat\n\n你好。", any);
        check(r.entry() != null && !r.entry().env.containsKey("weather")
                        && r.entry().env.containsKey("game_time") && hasIssue(r.issues(), "非标量"),
                "人类条目非标量 env 值应丢 key 并告警");
        UUID machineId = UUID.randomUUID();
        r = PrebuiltEntryCodec.readMachine("---\nschemaVersion: 3\nentryId: \"" + machineId
                + "\"\ncreatedAt: \"26-10-18-09:31:02\"\nchatLanguage: \"zh_cn\"\n"
                + "env:\n  weather: [Sunny]\n---\n\n## Chat\n\n你好。", "entry-" + machineId + ".md");
        check(r.entry() == null && hasIssue(r.issues(), "非标量"), "机器条目非标量 env 应整文件拒绝");
        // frontmatter 解析失败：人类条目降级为无头全文
        r = PrebuiltEntryCodec.readCustom("---\nenv: [unclosed\n\n正文还在。", any);
        check(r.entry() != null && r.entry().chatText.contains("正文还在。")
                        && hasIssue(r.issues(), "YAML 头"),
                "frontmatter 解析失败应告警且按正文处理");
        // frontmatter 未闭合同理
        r = PrebuiltEntryCodec.readCustom("---\nchatLanguage: \"zh_cn\"\n\n全文。", any);
        check(r.entry() != null && r.entry().chatText.contains("全文。")
                        && hasIssue(r.issues(), "---"),
                "未闭合 frontmatter 应告警且整篇按正文");
    }

    /** 6. 相对路径派生 entryId：稳定且路径不同则不同 */
    private static void derivedEntryIdStability() {
        String rel = "_global/welcome/早上好.md";
        UUID a = UUID.nameUUIDFromBytes(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID b = UUID.nameUUIDFromBytes(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(a.equals(b), "同一相对路径应派生相同 entryId");
        UUID c = UUID.nameUUIDFromBytes("_global/welcome/早上好.md ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID d = UUID.nameUUIDFromBytes("灵梦/welcome/早上好.md".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(!a.equals(c) && !a.equals(d), "路径不同应派生不同 entryId（改名即新条目）");
    }

    /** 7. 机器条目严格校验：schema、文件名与 entryId 不一致、缺必填字段均跳过 */
    private static void machineStrictValidation() {
        UUID id = UUID.randomUUID();
        String head = "---\nschemaVersion: 3\nentryId: \"" + id
                + "\"\ncreatedAt: \"26-10-18-09:31:02\"\nchatLanguage: \"zh_cn\"\n---\n\n## Chat\n\n你好。";
        check(PrebuiltEntryCodec.readMachine(head, "entry-" + id + ".md").entry() != null,
                "合法机器条目应解析成功");
        check(PrebuiltEntryCodec.readMachine(head, "entry-" + UUID.randomUUID() + ".md").entry() == null,
                "文件名与 entryId 不一致应跳过");
        String wrongSchema = head.replace("schemaVersion: 3", "schemaVersion: 2");
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readMachine(wrongSchema, "entry-" + id + ".md");
        check(r.entry() == null && hasIssue(r.issues(), "schemaVersion"), "schema 不是 3 应跳过并告警");
        check(PrebuiltEntryCodec.readMachine(
                head.replace("chatLanguage: \"zh_cn\"\n", ""), "entry-" + id + ".md").entry() == null,
                "缺 chatLanguage 应跳过");
        // 完全无头：机器条目不可用，人类条目可用
        check(PrebuiltEntryCodec.readMachine("你好。", "entry-" + id + ".md").entry() == null,
                "机器条目无头应跳过");
    }

    private static boolean hasIssue(List<PrebuiltEntryCodec.Issue> issues, String keyword) {
        return issues.stream().anyMatch(issue -> issue.message().contains(keyword));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("PrebuiltEntryCodecTest: " + message);
        }
    }
}
