package com.maidmod.selftalk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 预制复用 YAML/Markdown 编解码与人类条目匹配自检（无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * 覆盖（fenced-v1，issue #122）：机器条目编解码往返（含正文反引号）、围栏正文规则
 * （未知/重复 H2、段外散文、缺围栏、未闭合、非法标注均拒绝）、BOM/CRLF/Unicode/
 * 首尾空白保留/不同长度反引号、段缺失与双空回退、env 原始标量（yes/on/off/1:20/
 * 12:30:45/2026-10-18 与引号写法一致）、非法 env 整条拒绝、人类条目 AND 匹配、
 * 诊断告警、相对路径派生 entryId 稳定性、机器条目严格校验（旧 schema / 缺 bodyFormat）。
 * 已由 {@code gradle check} 真实执行，失败即构建失败。
 */
public final class PrebuiltEntryCodecTest {

    private static int checks = 0;

    private PrebuiltEntryCodecTest() {
    }

    public static void main(String[] args) {
        machineRoundTrip();
        fencedBodyCases();
        encodingEdges();
        fallbackCases();
        envScalarCases();
        envRejectCases();
        andMatchSemantics();
        derivedEntryIdStability();
        machineStrictValidation();
        System.out.println("PrebuiltEntryCodecTest passed: " + checks + " checks");
    }

    /** custom 条目最小合法头 + 正文 */
    private static String custom(String body) {
        return "---\nbodyFormat: \"fenced-v1\"\n---\n\n" + body;
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
        // 写出内容：schemaVersion 4 + bodyFormat 围栏声明 + 字符串标量双引号（防 YAML 隐式类型转换）
        String raw = PrebuiltEntryCodec.writeMachine(entry);
        check(raw.contains("schemaVersion: 4") && raw.contains("bodyFormat: \"fenced-v1\""),
                "机器条目应写出 schemaVersion 4 与 bodyFormat 声明");
        check(raw.contains("createdAt: \"26-10-18-09:31:02\"") && raw.contains("game_time: \"09:31\""),
                "字符串标量应写成双引号");
        // 正文含反引号围栏：写出围栏必须长于正文内最长连续反引号，往返保持原文
        SelfTalkReuseStore.PrebuiltEntry code = new SelfTalkReuseStore.PrebuiltEntry(
                id, "26-10-18-09:31:02", Map.of(), "zh_cn", "```\ncode\n```", null, null,
                SelfTalkReuseStore.Origin.MACHINE);
        PrebuiltEntryCodec.ReadResult read3 = PrebuiltEntryCodec.readMachine(
                PrebuiltEntryCodec.writeMachine(code), "entry-" + id + ".md");
        check(read3.entry() != null && read3.entry().chatText.equals("```\ncode\n```")
                        && read3.issues().isEmpty(),
                "正文含围栏应加长写出围栏并原样往返");
    }

    /** 2. 围栏正文规则：正常分段、标题大小写、未知/重复 H2、段外散文、缺围栏、未闭合、非法标注 */
    private static void fencedBodyCases() {
        UUID any = UUID.nameUUIDFromBytes("x".getBytes());
        // 只有 Chat / 只有 TTS / 两段齐备 / 大小写不敏感
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readCustom(
                custom("## Chat\n\n```\n你好。\n```"), any);
        check(r.entry() != null && r.entry().chatText.equals("你好。")
                && r.entry().ttsText.equals("你好。"), "只 Chat 段应回退 TTS");
        r = PrebuiltEntryCodec.readCustom(custom("## TTS\n\n```\n你好呀。\n```"), any);
        check(r.entry() != null && r.entry().chatText.equals("你好呀。")
                && r.entry().ttsText.equals("你好呀。"), "只 TTS 段应同时作为两段");
        r = PrebuiltEntryCodec.readCustom(custom("## chat\n\n```\n上屏。\n```\n\n## TTS\n\n```\n语音。\n```"), any);
        check(r.entry() != null && r.entry().chatText.equals("上屏。")
                && r.entry().ttsText.equals("语音。"), "标题大小写不敏感且两段各取各的");
        // 未知 H2：整条拒绝（不再忽略整段）
        r = PrebuiltEntryCodec.readCustom(custom("## Note\n\n```\n忽略。\n```\n\n## Chat\n\n```\n保留。\n```"), any);
        check(r.entry() == null && hasIssue(r.issues(), "未识别的二级标题"),
                "未知 H2 应整条拒绝并定位告警");
        // 重复段：整条拒绝
        r = PrebuiltEntryCodec.readCustom(
                custom("## Chat\n\n```\n一。\n```\n\n## Chat\n\n```\n二。\n```"), any);
        check(r.entry() == null && hasIssue(r.issues(), "重复"), "重复 Chat 段应整条拒绝");
        // 首个标题前的非空散文：整条拒绝
        r = PrebuiltEntryCodec.readCustom(custom("开头散文。\n\n## Chat\n\n```\n你好。\n```"), any);
        check(r.entry() == null && hasIssue(r.issues(), "标题外的散文本"),
                "段外非空散文应整条拒绝");
        // 两段之间的非空散文：整条拒绝
        r = PrebuiltEntryCodec.readCustom(
                custom("## Chat\n\n```\n你好。\n```\n\n中间散文。\n\n## TTS\n\n```\n语音。\n```"), any);
        check(r.entry() == null && hasIssue(r.issues(), "标题外的散文本"),
                "段间非空散文应整条拒绝");
        // 段内缺围栏：整条拒绝
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n你好。"), any);
        check(r.entry() == null && hasIssue(r.issues(), "围栏"),
                "段正文缺围栏应整条拒绝");
        // 未闭合围栏：整条拒绝
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n```\n你好。"), any);
        check(r.entry() == null && hasIssue(r.issues(), "未闭合"), "未闭合围栏应整条拒绝");
        // 关闭围栏与开启等长要求：不等长按正文处理 → 未闭合拒绝
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n```\n你好。\n````"), any);
        check(r.entry() == null && hasIssue(r.issues(), "未闭合"),
                "关闭围栏与开启不等长应按未闭合拒绝");
        // 非法围栏标注：整条拒绝
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n```java\n你好。\n```"), any);
        check(r.entry() == null && hasIssue(r.issues(), "围栏标注"), "非 text 围栏标注应整条拒绝");
        // 围栏内的 H2 与反引号都是正文，不参与标题识别
        r = PrebuiltEntryCodec.readCustom(
                custom("## Chat\n\n````\n## TTS\n```\n不是标题。\n````"), any);
        check(r.entry() != null && r.entry().chatText.equals("## TTS\n```\n不是标题。"),
                "围栏内 H2 应作为正文");
    }

    /** 3. BOM、CRLF、Unicode、首尾空白保留、内部换行、不同长度反引号、缩进 */
    private static void encodingEdges() {
        UUID any = UUID.nameUUIDFromBytes("x".getBytes());
        String content = "﻿---\r\nbodyFormat: \"fenced-v1\"\r\nenv:\r\n  weather: \"Sunny\"\r\n---\r\n\r\n"
                + "## Chat\r\n\r\n```\r\n  天气不错。\r\n```\r\n\r\n## TTS\r\n\r\n```\r\n  天气不错呢。\r\n```\r\n";
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readCustom(content, any);
        check(r.entry() != null && "Sunny".equals(r.entry().env.get("weather")),
                "BOM 与 CRLF 应被剥离，env 正常解析");
        check(r.entry() != null && r.entry().chatText.equals("  天气不错。")
                && r.entry().ttsText.equals("  天气不错呢。"), "首尾空白应原样保留");
        String multiline = "第一行\n第二行";
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n```\n" + multiline + "\n```"), any);
        check(r.entry() != null && r.entry().chatText.equals(multiline), "内部换行应保留");
        // 孤立 CR 单独换行
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n```\nA\rB\n```"), any);
        check(r.entry() != null && r.entry().chatText.equals("A\nB"), "孤立 CR 应统一为 LF");
        // 缩进的标题行在围栏内是正文
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n```\n  ## Chat\n  缩进正文\n```"), any);
        check(r.entry() != null && r.entry().chatText.equals("  ## Chat\n  缩进正文"),
                "围栏内缩进标题与缩进应作为正文保留");
        // 四反引号围栏往返（正文含三反引号行）与五反引号（正文含整行四反引号）
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n````\n```\n代码\n```\n````"), any);
        check(r.entry() != null && r.entry().chatText.equals("```\n代码\n```"),
                "四反引号围栏应正确往返");
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n`````\n````\n`````"), any);
        check(r.entry() != null && r.entry().chatText.equals("````"),
                "五反引号围栏包裹整行四反引号应正确往返");
    }

    /** 4. 回退规则：Chat 空白以 TTS 补、TTS 空白以 Chat 补、双空拒绝 */
    private static void fallbackCases() {
        UUID any = UUID.nameUUIDFromBytes("x".getBytes());
        // Chat 段空围栏：以 TTS 补上屏文本
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readCustom(
                custom("## Chat\n\n```\n```\n\n## TTS\n\n```\n语音文本。\n```"), any);
        check(r.entry() != null && r.entry().chatText.equals("语音文本。")
                        && r.entry().ttsText.equals("语音文本。"),
                "Chat 全空白应以 TTS 补上屏文本");
        // TTS 段全空白：以上屏文本补语音
        r = PrebuiltEntryCodec.readCustom(
                custom("## Chat\n\n```\n上屏文本。\n```\n\n## TTS\n\n```\n   \n```"), any);
        check(r.entry() != null && r.entry().chatText.equals("上屏文本。")
                        && r.entry().ttsText.equals("上屏文本。"),
                "TTS 全空白应以上屏文本补足");
        // 双空：整条拒绝
        r = PrebuiltEntryCodec.readCustom(
                custom("## Chat\n\n```\n```\n\n## TTS\n\n```\n \n```"), any);
        check(r.entry() == null && hasIssue(r.issues(), "缺少可用的上屏文本"),
                "两段均空白应整条拒绝");
        // 标题后无任何内容（连围栏都没有）同样视为空段走回退/拒绝
        r = PrebuiltEntryCodec.readCustom(custom("## Chat\n\n## TTS\n\n```\n只有语音。\n```"), any);
        check(r.entry() != null && r.entry().chatText.equals("只有语音。"),
                "空 Chat 段应以 TTS 补上屏文本");
    }

    /** 5. env 原始标量：未加引号的字面值与引号写法一致；schemaVersion 仍按数值读取 */
    private static void envScalarCases() {
        UUID any = UUID.nameUUIDFromBytes("x".getBytes());
        String unquoted = "---\nbodyFormat: \"fenced-v1\"\nenv:\n"
                + "  weather: yes\n"
                + "  time_period: off\n"
                + "  game_time: 1:20\n"
                + "  clock: 12:30:45\n"
                + "  date: 2026-10-18\n"
                + "---\n\n## Chat\n\n```\n你好。\n```";
        String quoted = "---\nbodyFormat: \"fenced-v1\"\nenv:\n"
                + "  weather: \"yes\"\n"
                + "  time_period: \"off\"\n"
                + "  game_time: \"1:20\"\n"
                + "  clock: \"12:30:45\"\n"
                + "  date: \"2026-10-18\"\n"
                + "---\n\n## Chat\n\n```\n你好。\n```";
        PrebuiltEntryCodec.ReadResult a = PrebuiltEntryCodec.readCustom(unquoted, any);
        PrebuiltEntryCodec.ReadResult b = PrebuiltEntryCodec.readCustom(quoted, any);
        check(a.entry() != null && b.entry() != null, "两类 env 写法均应解析成功");
        check(a.entry().env.equals(b.entry().env),
                "未加引号的 yes/off/1:20/12:30:45/2026-10-18 应与引号写法一致，实际 "
                        + a.entry().env + " vs " + b.entry().env);
        check("yes".equals(a.entry().env.get("weather")) && "1:20".equals(a.entry().env.get("game_time"))
                        && "12:30:45".equals(a.entry().env.get("clock"))
                        && "2026-10-18".equals(a.entry().env.get("date")),
                "字面值应原样保留（不做 YAML 1.1 隐式转换）");
        // 机器条目 schemaVersion 无引号数字仍按数值读取
        UUID id = UUID.randomUUID();
        PrebuiltEntryCodec.ReadResult m = PrebuiltEntryCodec.readMachine(
                "---\nschemaVersion: 4\nbodyFormat: \"fenced-v1\"\nentryId: \"" + id
                        + "\"\ncreatedAt: \"26-10-18-09:31:02\"\nchatLanguage: \"zh_cn\"\n---\n\n"
                        + "## Chat\n\n```\n你好。\n```", "entry-" + id + ".md");
        check(m.entry() != null && m.issues().isEmpty(), "无引号 schemaVersion 数字应按数值读取");
    }

    /** 6. 非法 env：非映射、复合值、复合 key、空值整条拒绝，不丢 key 后继续匹配 */
    private static void envRejectCases() {
        UUID any = UUID.nameUUIDFromBytes("x".getBytes());
        // 显式非映射
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readCustom(
                "---\nbodyFormat: \"fenced-v1\"\nenv: [Sunny]\n---\n\n## Chat\n\n```\n你好。\n```", any);
        check(r.entry() == null && hasIssue(r.issues(), "整条拒绝"), "env 显式非映射应整条拒绝");
        // 复合值
        r = PrebuiltEntryCodec.readCustom(
                "---\nbodyFormat: \"fenced-v1\"\nenv:\n  weather: [Sunny]\n  game_time: \"09:31\"\n---\n\n"
                        + "## Chat\n\n```\n你好。\n```", any);
        check(r.entry() == null && hasIssue(r.issues(), "整条拒绝"),
                "env 复合值应整条拒绝（不丢 key 继续）");
        // 复合 key
        r = PrebuiltEntryCodec.readCustom(
                "---\nbodyFormat: \"fenced-v1\"\nenv:\n  ? [a, b]\n  : v\n---\n\n## Chat\n\n```\n你好。\n```", any);
        check(r.entry() == null && hasIssue(r.issues(), "复合 key"), "env 复合 key 应整条拒绝");
        // 空值（YAML null）
        r = PrebuiltEntryCodec.readCustom(
                "---\nbodyFormat: \"fenced-v1\"\nenv:\n  weather:\n---\n\n## Chat\n\n```\n你好。\n```", any);
        check(r.entry() == null && hasIssue(r.issues(), "整条拒绝"), "env 空值应整条拒绝");
        // 机器条目同理
        UUID machineId = UUID.randomUUID();
        r = PrebuiltEntryCodec.readMachine("---\nschemaVersion: 4\nbodyFormat: \"fenced-v1\"\nentryId: \""
                        + machineId + "\"\ncreatedAt: \"26-10-18-09:31:02\"\nchatLanguage: \"zh_cn\"\n"
                        + "env:\n  weather: [Sunny]\n---\n\n## Chat\n\n```\n你好。\n```",
                "entry-" + machineId + ".md");
        check(r.entry() == null && hasIssue(r.issues(), "整条拒绝"), "机器条目非法 env 应整文件拒绝");
        // 未知环境 key：保留并告警、条目可用
        r = PrebuiltEntryCodec.readCustom(
                "---\nbodyFormat: \"fenced-v1\"\nenv:\n  wether: \"Sunny\"\n---\n\n## Chat\n\n```\n你好。\n```", any);
        check(r.entry() != null && r.entry().env.containsKey("wether")
                        && hasIssue(r.issues(), "未知环境 key"),
                "未知环境 key 应保留并告警");
    }

    /** 7. 人类条目 AND 匹配语义：空 env 恒通过、部分 key 全相等通过、任一不等/缺失不通过 */
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
                "---\nbodyFormat: \"fenced-v1\"\nenv:\n  weather: \"Sunny\"\n---\n\n## Chat\n\n```\n天气不错。\n```",
                any);
        check(r.entry() != null && SelfTalkReuseService.allWrittenKeysEqual(r.entry().env, current),
                "解析后的人类条目应参与 AND 匹配");
        check(r.entry() != null && r.entry().chatLanguage == null, "缺 chatLanguage 应表示语言通配");
    }

    /** 8. 相对路径派生 entryId：稳定且路径不同则不同 */
    private static void derivedEntryIdStability() {
        String rel = "_global/welcome/早上好.md";
        UUID a = UUID.nameUUIDFromBytes(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID b = UUID.nameUUIDFromBytes(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(a.equals(b), "同一相对路径应派生相同 entryId");
        UUID c = UUID.nameUUIDFromBytes("_global/welcome/早上好.md ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID d = UUID.nameUUIDFromBytes("灵梦/welcome/早上好.md".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(!a.equals(c) && !a.equals(d), "路径不同应派生不同 entryId（改名即新条目）");
    }

    /** 9. 严格校验：旧 schema、缺/错 bodyFormat、文件名不符、缺必填字段、无头文件均拒绝；createdAt 补足保留 */
    private static void machineStrictValidation() {
        UUID id = UUID.randomUUID();
        String head = "---\nschemaVersion: 4\nbodyFormat: \"fenced-v1\"\nentryId: \"" + id
                + "\"\ncreatedAt: \"26-10-18-09:31:02\"\nchatLanguage: \"zh_cn\"\n---\n\n"
                + "## Chat\n\n```\n你好。\n```";
        check(PrebuiltEntryCodec.readMachine(head, "entry-" + id + ".md").entry() != null,
                "合法机器条目应解析成功");
        check(PrebuiltEntryCodec.readMachine(head, "entry-" + UUID.randomUUID() + ".md").entry() == null,
                "文件名与 entryId 不一致应拒绝");
        // 旧 schema（3）：拒绝入池
        PrebuiltEntryCodec.ReadResult r = PrebuiltEntryCodec.readMachine(
                head.replace("schemaVersion: 4", "schemaVersion: 3"), "entry-" + id + ".md");
        check(r.entry() == null && hasIssue(r.issues(), "schemaVersion"), "旧 schema 应拒绝并告警");
        // 缺 bodyFormat / 未知 bodyFormat：拒绝入池
        r = PrebuiltEntryCodec.readMachine(head.replace("bodyFormat: \"fenced-v1\"\n", ""),
                "entry-" + id + ".md");
        check(r.entry() == null && hasIssue(r.issues(), "bodyFormat"), "缺 bodyFormat 应拒绝");
        r = PrebuiltEntryCodec.readMachine(head.replace("fenced-v1", "v2"), "entry-" + id + ".md");
        check(r.entry() == null && hasIssue(r.issues(), "bodyFormat"), "未知 bodyFormat 应拒绝");
        check(PrebuiltEntryCodec.readMachine(
                head.replace("chatLanguage: \"zh_cn\"\n", ""), "entry-" + id + ".md").entry() == null,
                "缺 chatLanguage 应拒绝");
        // 完全无头：机器条目与人类条目均拒绝（旧 custom 无头停用）
        check(PrebuiltEntryCodec.readMachine("你好。", "entry-" + id + ".md").entry() == null,
                "机器条目无头应拒绝");
        PrebuiltEntryCodec.ReadResult c = PrebuiltEntryCodec.readCustom("你好。", id);
        check(c.entry() == null && hasIssue(c.issues(), "YAML 头"), "人类条目无头应拒绝并告警");
        // 旧 custom（有头无 bodyFormat）：拒绝
        c = PrebuiltEntryCodec.readCustom("---\n---\n\n## Chat\n\n```\n你好。\n```", id);
        check(c.entry() == null && hasIssue(c.issues(), "bodyFormat"), "旧 custom 缺 bodyFormat 应拒绝");
        // createdAt 缺失：无时间来源拒绝；有文件时间来源则补足（保留 #107 行为）
        String noDate = head.replace("createdAt: \"26-10-18-09:31:02\"\n", "");
        check(PrebuiltEntryCodec.readMachine(noDate, "entry-" + id + ".md").entry() == null,
                "无时间来源缺 createdAt 应拒绝");
        PrebuiltEntryCodec.ReadResult backfill = PrebuiltEntryCodec.readMachine(noDate, "entry-" + id + ".md",
                () -> java.time.Instant.parse("2026-10-18T01:02:03Z"));
        check(backfill.entry() != null && !backfill.entry().createdAt.isBlank()
                        && hasIssue(backfill.issues(), "补足"),
                "缺 createdAt 应按文件时间补足并告警");
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
