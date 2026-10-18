package com.maidmod.selftalk;

import org.apache.commons.lang3.StringUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 预制复用条目的 YAML frontmatter + Markdown 正文编解码器（机器条目 schemaVersion 3）。
 * <p>
 * 文件形态：{@code ---} 独立行之间的 YAML 头 + 按 {@code ## Chat} / {@code ## TTS}
 * 二级标题（大小写不敏感、比较前 trim）分段的正文。本类刻意不依赖 Minecraft / TLM 类型
 * （环境 key 白名单用 {@link EnvironmentContextOption}，它是纯数据类），可脱离游戏自检。
 * <p>
 * 写出（仅机器条目）：frontmatter 用 block 风格，所有字符串标量统一双引号，
 * 规避 YAML 隐式类型转换（{@code yes/no/on/off/null/~}、数字、时间）；正文固定
 * {@code ## Chat} 与（有 TTS 时）{@code ## TTS} 两段。{@code ttsLanguage}/{@code ttsText}
 * 为 null 时整个键省略。
 * <p>
 * 读取：剥离 UTF-8 BOM，统一 LF/CRLF/CR 为 LF；frontmatter 只在独立的 {@code ---} 行之间解析，
 * 头解析失败时该文件按无头处理（人类条目降级为全文正文，机器条目因缺必填字段自然跳过）；
 * {@code env} 必须是扁平映射，值允许标量（字符串/数字/布尔）并转字符串，
 * list/map 等非标量拒绝——机器条目整文件跳过，人类条目只丢该 key；
 * 未知环境 key（不在 {@link EnvironmentContextOption#ALL} 目录内）产生告警（不参与匹配）。
 * 正文分段规则见 {@link #readBody}：无任何已识别 H2 时全文即 chatText；
 * 只有 TTS 段时 TTS 文本同时作为 chatText；缺 TTS 段时 ttsText 回退 chatText；
 * 未识别 H2 整段忽略并告警；首个已识别标题之前的散文本忽略并提示。
 * <p>
 * 机器条目严格校验（任一不满足整文件跳过）：{@code schemaVersion == 3}、{@code entryId}
 * 合法 UUID 且与 {@code entry-<UUID>.md} 文件名一致、{@code createdAt}/{@code chatLanguage}/
 * {@code chatText} 非空。人类条目宽松：允许无头、部分头、缺语言字段（缺 {@code chatLanguage}
 * 表示语言通配），{@code entryId}/{@code schemaVersion}/{@code createdAt} 一律不要求。
 * 全部方法仅服务端主线程调用；Yaml 实例非线程安全，每次调用各自新建。
 */
final class PrebuiltEntryCodec {

    /** 机器条目 schema 版本：3 = YAML 头 Markdown（旧 1 扁平 JSON / 2 指纹 JSON 不读不迁不删） */
    static final int SCHEMA_VERSION = 3;

    private PrebuiltEntryCodec() {
    }

    /** 单条解析告警：line 为 1 起的行号，定位不到时为 -1 */
    record Issue(int line, String message) {
    }

    /** 单文件解析结果：entry 为 null 表示该文件没有可用条目；issues 是该文件全部告警（可为空） */
    record ReadResult(SelfTalkReuseStore.PrebuiltEntry entry, List<Issue> issues) {
    }

    // ===== 写出（仅机器条目） =====

    /**
     * 单条条目 → YAML 头 + Markdown 正文。env 保序写出；ttsLanguage/ttsText 为 null 时省略键；
     * TTS 段仅在 ttsText 非空时写出。字符串值统一双引号（键保持原样，见 {@link Quoted}）。
     */
    static String writeMachine(SelfTalkReuseStore.PrebuiltEntry entry) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", SCHEMA_VERSION);
        meta.put("entryId", quoted(entry.entryId.toString()));
        meta.put("createdAt", quoted(entry.createdAt));
        if (entry.env != null && !entry.env.isEmpty()) {
            Map<String, Object> env = new LinkedHashMap<>();
            entry.env.forEach((key, value) -> env.put(key, quoted(value)));
            meta.put("env", env);
        }
        meta.put("chatLanguage", quoted(entry.chatLanguage));
        if (entry.ttsLanguage != null) {
            meta.put("ttsLanguage", quoted(entry.ttsLanguage));
        }
        StringBuilder sb = new StringBuilder("---\n").append(dumpMeta(meta)).append("---\n\n")
                .append("## Chat\n\n").append(entry.chatText).append('\n');
        if (entry.ttsText != null) {
            sb.append("\n## TTS\n\n").append(entry.ttsText).append('\n');
        }
        return sb.toString();
    }

    /** block 风格；字符串值经 {@link Quoted} 包装后一律双引号，键与数字保持默认表示 */
    private static String dumpMeta(Map<String, Object> meta) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        return new Yaml(new QuotedRepresenter(options), options).dump(meta);
    }

    /** 字符串值的强制双引号标记（不影响映射键，键均为本 mod 固定字段名与环境 key） */
    private record Quoted(String value) {
    }

    private static Object quoted(String value) {
        return new Quoted(value);
    }

    /** Quoted 包装的字符串以 DOUBLE_QUOTED 标量写出，规避 YAML 隐式类型转换 */
    private static final class QuotedRepresenter extends Representer {

        QuotedRepresenter(DumperOptions options) {
            super(options);
            representers.put(Quoted.class, data ->
                    representScalar(Tag.STR, ((Quoted) data).value(), DumperOptions.ScalarStyle.DOUBLE_QUOTED));
        }
    }

    // ===== 读取 =====

    /** 机器条目读取（严格校验，见类注释）；entry 为 null 表示跳过该文件 */
    static ReadResult readMachine(String content, String fileName) {
        List<Issue> issues = new ArrayList<>();
        Parse p = parse(content, issues);
        if (p.meta() == null) {
            // 无头 / 未闭合 / 解析失败：机器条目缺少必填元数据，直接不可用（告警已在 parse 里记）
            return new ReadResult(null, issues);
        }
        Map<String, Object> meta = p.meta();
        Object schema = meta.get("schemaVersion");
        if (!(schema instanceof Number schemaNumber) || schemaNumber.intValue() != SCHEMA_VERSION) {
            issues.add(new Issue(locateLine(p.fmLines(), "schemaVersion"),
                    "schemaVersion 不是 " + SCHEMA_VERSION + "（旧版 JSON 条目不读取不迁移），跳过"));
            return new ReadResult(null, issues);
        }
        UUID entryId = parseEntryId(meta, issues, p.fmLines());
        if (entryId == null) {
            return new ReadResult(null, issues);
        }
        if (!("entry-" + entryId + ".md").equals(fileName)) {
            issues.add(new Issue(locateLine(p.fmLines(), "entryId"),
                    "entryId 与文件名不一致（应为 entry-" + entryId + ".md），跳过"));
            return new ReadResult(null, issues);
        }
        String createdAt = requireString(meta, "createdAt", issues, p.fmLines());
        if (createdAt == null) {
            return new ReadResult(null, issues);
        }
        String chatLanguage = requireString(meta, "chatLanguage", issues, p.fmLines());
        if (chatLanguage == null) {
            return new ReadResult(null, issues);
        }
        Map<String, String> env = readEnv(meta, issues, p.fmLines(), true);
        if (env == null) {
            // env 存在非标量值：机器条目整文件拒绝
            return new ReadResult(null, issues);
        }
        BodyResult body = readBody(p, issues);
        if (body.chatText() == null || body.chatText().isBlank()) {
            issues.add(new Issue(-1, "上屏文本为空，跳过"));
            return new ReadResult(null, issues);
        }
        String ttsLanguage = optionalString(meta, "ttsLanguage", issues, p.fmLines());
        return new ReadResult(new SelfTalkReuseStore.PrebuiltEntry(entryId, createdAt, env, chatLanguage,
                body.chatText(), ttsLanguage, body.ttsText(), SelfTalkReuseStore.Origin.MACHINE), issues);
    }

    /**
     * 人类条目读取（宽松，见类注释）。{@code derivedEntryId} 由调用方按相对路径派生
     * （{@code UUID.nameUUIDFromBytes(UTF-8(相对路径))}），只在内存里用。
     */
    static ReadResult readCustom(String content, UUID derivedEntryId) {
        List<Issue> issues = new ArrayList<>();
        Parse p = parse(content, issues);
        Map<String, Object> meta = p.meta() == null ? Map.of() : p.meta();
        Map<String, String> env = readEnv(meta, issues, p.fmLines(), false);
        String chatLanguage = optionalString(meta, "chatLanguage", issues, p.fmLines());
        String ttsLanguage = optionalString(meta, "ttsLanguage", issues, p.fmLines());
        String createdAt = optionalString(meta, "createdAt", issues, p.fmLines());
        BodyResult body = readBody(p, issues);
        if (body.chatText() == null || body.chatText().isBlank()) {
            issues.add(new Issue(-1, "空条目：没有可用的上屏文本，未加入候选池"));
            return new ReadResult(null, issues);
        }
        return new ReadResult(new SelfTalkReuseStore.PrebuiltEntry(derivedEntryId, createdAt, env,
                chatLanguage, body.chatText(), ttsLanguage, body.ttsText(),
                SelfTalkReuseStore.Origin.CUSTOM), issues);
    }

    // ===== 解析内部结构 =====

    /** 单文件解析中间态：meta 为 null 表示「无可用 YAML 头」（无头/未闭合/解析失败/不是映射） */
    private record Parse(Map<String, Object> meta, List<String> fmLines,
                         List<String> bodyLines, int bodyStartLine) {
    }

    private record BodyResult(String chatText, String ttsText) {
    }

    /**
     * 规范化并切分：剥 BOM、统一换行为 LF；首行是独立 {@code ---} 时按 frontmatter 处理，
     * 找不到结束行则整篇按正文（记告警）。行号一律 1 起、按规范化后的全文计。
     */
    private static Parse parse(String content, List<Issue> issues) {
        String normalized = stripBom(content).replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
        Map<String, Object> meta = null;
        List<String> fmLines = List.of();
        int bodyStart = 0;
        if (!lines.isEmpty() && lines.get(0).trim().equals("---")) {
            int close = -1;
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("---")) {
                    close = i;
                    break;
                }
            }
            if (close < 0) {
                issues.add(new Issue(1, "YAML 头以 --- 开头但没有结束的 --- 行，整篇按正文处理"));
            } else {
                fmLines = List.copyOf(lines.subList(1, close));
                bodyStart = close + 1;
                meta = parseFrontmatter(String.join("\n", fmLines), issues);
            }
        }
        return new Parse(meta, fmLines, lines.subList(bodyStart, lines.size()), bodyStart + 1);
    }

    /** frontmatter → 字符串键映射；解析失败/不是映射返回 null（调用方按无头处理） */
    private static Map<String, Object> parseFrontmatter(String fmText, List<Issue> issues) {
        try {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(fmText);
            if (root == null) {
                issues.add(new Issue(1, "YAML 头为空"));
                return Map.of();
            }
            if (!(root instanceof Map<?, ?> map)) {
                issues.add(new Issue(1, "YAML 头必须是键值映射，实际是 " + typeName(root)));
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        } catch (Exception e) {
            issues.add(new Issue(1, "YAML 头解析失败：" + e.getMessage()));
            return null;
        }
    }

    /**
     * 正文分段（规则见类注释）。散文本只在「存在已识别标题」时提示（无标题时它就是正文本身）；
     * 未知标题后的内容随「整段忽略」处理，不再并入散文本提示。
     */
    private static BodyResult readBody(Parse p, List<Issue> issues) {
        List<String> lines = p.bodyLines();
        int absStart = p.bodyStartLine();
        StringBuilder chat = null;
        StringBuilder tts = null;
        // 当前接收正文的缓冲：识别标题切换，未知标题置 null（其后内容随「整段忽略」丢弃）
        StringBuilder current = null;
        StringBuilder preamble = null;
        boolean anyHeading = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.startsWith("## ")) {
                anyHeading = true;
                String title = trimmed.substring(3).trim();
                String lower = title.toLowerCase(Locale.ROOT);
                if ("chat".equals(lower)) {
                    chat = new StringBuilder();
                    current = chat;
                } else if ("tts".equals(lower)) {
                    tts = new StringBuilder();
                    current = tts;
                } else {
                    current = null;
                    issues.add(new Issue(absStart + i, "未识别的二级标题：## " + title
                            + "，该段整段忽略（可用标题：## Chat / ## TTS，大小写不敏感）"));
                }
                continue;
            }
            if (current != null) {
                current.append(raw).append('\n');
            } else if (anyHeading) {
                // 未知标题段的正文：已随该标题整段忽略
            } else {
                if (preamble == null) {
                    preamble = new StringBuilder();
                }
                preamble.append(raw).append('\n');
            }
        }
        String chatText = chat == null ? null : chat.toString().trim();
        String ttsText = tts == null ? null : tts.toString().trim();
        String preambleText = preamble == null ? "" : preamble.toString().trim();
        if (chatText == null && ttsText == null) {
            // 无任何已识别 H2（含标题全是未识别的情况）：整篇正文即上屏文本（TTS 走下方统一回退）
            StringBuilder whole = new StringBuilder();
            for (String line : lines) {
                whole.append(line).append('\n');
            }
            chatText = whole.toString().trim();
        } else {
            if (!preambleText.isEmpty()) {
                issues.add(new Issue(-1, "首个标题之前的散文本已忽略：" + StringUtils.abbreviate(preambleText, 24)));
            }
            if (chatText == null) {
                // 只有 TTS 段：TTS 文本同时作为上屏文本
                chatText = ttsText;
            }
        }
        if (ttsText == null || ttsText.isBlank()) {
            // 缺 TTS 段：语音文本回退为上屏文本
            ttsText = chatText;
        }
        return new BodyResult(chatText, ttsText);
    }

    /**
     * 解析 env：必须是扁平映射；值为标量（字符串/数字/布尔/字符）时转字符串。
     * 非标量值：strict（机器条目）返回 null 表示整文件拒绝；宽松（人类条目）丢该 key 继续。
     * 未知环境 key 记告警（不参与匹配）。env 缺失按空映射处理。
     */
    private static Map<String, String> readEnv(Map<String, Object> meta, List<Issue> issues,
                                               List<String> fmLines, boolean strict) {
        Object raw = meta.get("env");
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            issues.add(new Issue(locateLine(fmLines, "env"),
                    "env 必须是扁平的键值映射，实际是 " + typeName(raw) + "，已忽略"));
            return new LinkedHashMap<>();
        }
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            if (!isScalar(value)) {
                issues.add(new Issue(locateLine(fmLines, key),
                        "env 值必须是标量（字符串/数字/布尔），key " + key + " 的值是非标量 " + typeName(value)
                                + (strict ? "，跳过该文件" : "，忽略该 key")));
                if (strict) {
                    return null;
                }
                continue;
            }
            if (EnvironmentContextOption.byKey(key) == null) {
                issues.add(new Issue(locateLine(fmLines, key),
                        "未知环境 key：" + key + "（该 key 不参与匹配，请对照报告中的环境 key 目录检查拼写）"));
            }
            env.put(key, String.valueOf(value));
        }
        return env;
    }

    /** 标量判定：字符串/数字/布尔/字符；null 与 list/map 均非标量 */
    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character;
    }

    private static String typeName(Object value) {
        return value == null ? "空值" : value.getClass().getSimpleName();
    }

    /** 可选字符串字段：缺失/空白返回 null；非标量记告警后按缺失处理 */
    private static String optionalString(Map<String, Object> meta, String key, List<Issue> issues,
                                         List<String> fmLines) {
        Object value = meta.get(key);
        if (value == null) {
            return null;
        }
        if (!isScalar(value)) {
            issues.add(new Issue(locateLine(fmLines, key), key + " 必须是标量，已按缺失处理"));
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    /** 机器条目必填字符串字段：缺失/空白记告警并返回 null（调用方跳过文件） */
    private static String requireString(Map<String, Object> meta, String key, List<Issue> issues,
                                        List<String> fmLines) {
        String s = optionalString(meta, key, issues, fmLines);
        if (s == null) {
            issues.add(new Issue(locateLine(fmLines, key), "机器条目缺少必填字段 " + key + "，跳过"));
        }
        return s;
    }

    /** 解析机器条目 entryId：缺失或不是合法 UUID 记告警并返回 null */
    private static UUID parseEntryId(Map<String, Object> meta, List<Issue> issues, List<String> fmLines) {
        String raw = optionalString(meta, "entryId", issues, fmLines);
        if (raw == null) {
            issues.add(new Issue(locateLine(fmLines, "entryId"), "机器条目缺少合法 entryId，跳过"));
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            issues.add(new Issue(locateLine(fmLines, "entryId"), "entryId 不是合法 UUID：" + raw + "，跳过"));
            return null;
        }
    }

    /** 在 frontmatter 原始行里定位某 key 的行号（1 起；frontmatter 从全文第 2 行开始）；找不到返回 -1 */
    private static int locateLine(List<String> fmLines, String key) {
        for (int i = 0; i < fmLines.size(); i++) {
            String trimmed = fmLines.get(i).trim();
            if (trimmed.startsWith("\"" + key + "\":") || trimmed.startsWith(key + ":")) {
                return i + 2;
            }
        }
        return -1;
    }

    private static final char UTF8_BOM = '\uFEFF';

    private static String stripBom(String content) {
        if (!content.isEmpty() && content.charAt(0) == UTF8_BOM) {
            return content.substring(1);
        }
        return content;
    }
}
