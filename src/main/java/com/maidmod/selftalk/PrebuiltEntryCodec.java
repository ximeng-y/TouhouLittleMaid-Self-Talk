package com.maidmod.selftalk;

import org.apache.commons.lang3.StringUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 预制复用条目的 YAML frontmatter + Markdown 正文编解码器（机器条目 schemaVersion 4）。
 * <p>
 * 文件形态：{@code ---} 独立行之间的 YAML 头 + 按 {@code ## Chat} / {@code ## TTS}
 * 二级标题（大小写不敏感、比较前 trim）组织的两个反引号围栏段（{@code bodyFormat: "fenced-v1"}）。
 * 本类刻意不依赖 Minecraft / TLM 类型（环境 key 白名单用 {@link EnvironmentContextOption}，
 * 它是纯数据类），可脱离游戏自检。
 * <p>
 * 写出（仅机器条目）：frontmatter 用 block 风格，所有字符串标量统一双引号，规避 YAML
 * 隐式类型转换（{@code yes/no/on/off/null/~}、数字、时间）；正文每段放入反引号围栏，
 * 围栏长度取至少 3 且大于该正文中最长连续反引号。{@code ttsLanguage}/{@code ttsText}
 * 为 null 时整个 TTS 段省略。
 * <p>
 * 读取：剥离 UTF-8 BOM，统一 LF/CRLF/CR 为 LF；frontmatter 只在独立的 {@code ---} 行之间解析。
 * 两类条目都要求有 YAML 头并声明 {@code bodyFormat: "fenced-v1"}——旧 schema 机器条目、
 * 无头 custom、缺少或未知 {@code bodyFormat}、损坏 YAML 一律诊断并拒绝入池；
 * 原件不改写、不迁移、不删除。围栏正文规则见 {@link #readBody}：标题只在围栏外识别，
 * 围栏内所有 H2、缩进与反引号都是正文；开启围栏接受无信息串或 {@code text} 标注，
 * 关闭围栏须与开启围栏等长；每种段最多一个，重复段、未知段、未闭合围栏或段外非空散文
 * 一律拒绝并定位告警。段正文原文保留（仅去掉围栏结构的换行，首尾空白不修剪）；
 * Chat 段缺失、空或全空白且 TTS 有效时以 TTS 补 Chat，TTS 无有效内容时以 Chat 补足，
 * 双空整条拒绝。
 * <p>
 * {@code env} 用 SafeConstructor 的局部构造分支按原始文本构造（见 {@link FencedEnvConstructor}）：
 * 标量键值保持 {@code yes/on/off}、{@code 1:20}、日期等字面值，显式非映射、复合键、
 * 复合值或空值整条拒绝（不丢 key 后继续匹配）；未知环境 key（不在
 * {@link EnvironmentContextOption#ALL} 目录内）保留并告警（不参与匹配）。
 * <p>
 * 机器条目严格校验（任一不满足整文件拒绝）：{@code schemaVersion == 4}、{@code entryId}
 * 合法 UUID 且与 {@code entry-<UUID>.md} 文件名一致、{@code createdAt}/{@code chatLanguage}
 * 非空。唯一例外：从文件读取机器条目时（带文件时间来源的 {@link #readMachine(String, String, Supplier)}
 * 重载），{@code createdAt} 缺失或空白不直接拒绝，而用文件最后修改时间补足并记告警
 * （格式与时区见 {@link #formatCreatedAt}）；日期为列表/映射等非标量仍拒绝，其余校验不放宽。
 * 人类条目宽松：允许部分头、缺语言字段（缺 {@code chatLanguage} 表示语言通配），
 * {@code entryId}/{@code schemaVersion}/{@code createdAt} 一律不要求，但头部与
 * {@code bodyFormat} 声明仍必须齐全。
 * 全部方法仅服务端主线程调用；Yaml 实例非线程安全，每次调用各自新建。
 */
final class PrebuiltEntryCodec {

    /** 机器条目 schema 版本：4 = fenced-v1 围栏正文（旧 3 整段 Markdown / 1 扁平 JSON / 2 指纹 JSON 不读不迁不删） */
    static final int SCHEMA_VERSION = 4;

    /** 正文格式标记：两类条目的 YAML 头都必须声明该值（围栏正文 v1） */
    static final String BODY_FORMAT = "fenced-v1";

    /** 入库时间格式（服务端本地时间；yy 非 YYYY、mm 为分钟，写错即时间序全乱） */
    private static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yy-MM-dd-HH:mm:ss");

    private PrebuiltEntryCodec() {
    }

    /**
     * 机器条目 createdAt 的统一格式化入口：正常生成（当前时刻）与缺日期补足（文件最后
     * 修改时间）共用同一格式与时区口径（服务器 JVM 默认时区）。不在方法内部读取当前
     * 时间，由调用方明确提供时刻来源。
     */
    static String formatCreatedAt(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(CREATED_AT_FORMAT);
    }

    /**
     * 单条解析告警：line 为 1 起的行号，定位不到时为 -1。
     * <p>
     * zh/en 是同一条告警的中英文文案，由诊断报告按配置语言选择；两套文案内容必须一致，
     * 只允许措辞与标点不同。
     */
    record Issue(int line, String zh, String en) {

        /** 按语言取文案：chinese 为 true 取中文，否则取英文 */
        String message(boolean chinese) {
            return chinese ? zh : en;
        }
    }

    /** 单文件解析结果：entry 为 null 表示该文件没有可用条目；issues 是该文件全部告警（可为空） */
    record ReadResult(SelfTalkReuseStore.PrebuiltEntry entry, List<Issue> issues) {
    }

    // ===== 写出（仅机器条目） =====

    /**
     * 单条条目 → YAML 头 + Markdown 正文。env 保序写出；ttsLanguage/ttsText 为 null 时
     * 省略 TTS 段；字符串值统一双引号（键保持原样，见 {@link Quoted}）；正文围栏长度
     * 按 {@link #fenced} 规则取值。
     */
    static String writeMachine(SelfTalkReuseStore.PrebuiltEntry entry) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", SCHEMA_VERSION);
        meta.put("bodyFormat", quoted(BODY_FORMAT));
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
                .append("## Chat\n\n").append(fenced(entry.chatText)).append('\n');
        if (entry.ttsText != null) {
            sb.append("\n## TTS\n\n").append(fenced(entry.ttsText)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 正文反引号围栏：围栏长度取至少 3，且大于正文中最长连续反引号，
     * 保证围栏内的任意反引号内容（含整行反引号）不会被误认成关闭围栏。
     */
    private static String fenced(String body) {
        String fence = "`".repeat(Math.max(3, maxBacktickRun(body) + 1));
        return fence + "\n" + body + "\n" + fence;
    }

    /** 正文中最长连续反引号长度 */
    private static int maxBacktickRun(String text) {
        int max = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                run++;
                if (run > max) {
                    max = run;
                }
            } else {
                run = 0;
            }
        }
        return max;
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

    /**
     * 机器条目读取（严格校验，见类注释）；无文件时间来源，{@code createdAt} 缺失或空白
     * 仍按缺失拒绝。entry 为 null 表示拒绝该文件。
     */
    static ReadResult readMachine(String content, String fileName) {
        return readMachine(content, fileName, null);
    }

    /**
     * 机器条目读取（严格校验，见类注释）；{@code lastModifiedTime} 提供所在文件的最后
     * 修改时间来源，惰性调用——仅当 {@code createdAt} 缺失或空白时才取，一次解析至多
     * 一次，读取失败只拒绝本文件。entry 为 null 表示拒绝该文件。
     */
    static ReadResult readMachine(String content, String fileName, Supplier<Instant> lastModifiedTime) {
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
                    "schemaVersion 不是 " + SCHEMA_VERSION + "（旧格式条目不读取不迁移，原件保留），拒绝入池",
                    "schemaVersion is not " + SCHEMA_VERSION + " (old-format entries are neither read nor "
                            + "migrated and the original file is kept), so the entry is not added to the pool"));
            return new ReadResult(null, issues);
        }
        if (!requireBodyFormat(meta, issues, p.fmLines())) {
            return new ReadResult(null, issues);
        }
        UUID entryId = parseEntryId(meta, issues, p.fmLines());
        if (entryId == null) {
            return new ReadResult(null, issues);
        }
        if (!("entry-" + entryId + ".md").equals(fileName)) {
            issues.add(new Issue(locateLine(p.fmLines(), "entryId"),
                    "entryId 与文件名不一致（应为 entry-" + entryId + ".md），拒绝该文件",
                    "entryId does not match the file name (expected entry-" + entryId
                            + ".md); the file is rejected"));
            return new ReadResult(null, issues);
        }
        String createdAt = resolveCreatedAt(meta, issues, p.fmLines(), lastModifiedTime);
        if (createdAt == null) {
            return new ReadResult(null, issues);
        }
        String chatLanguage = requireString(meta, "chatLanguage", issues, p.fmLines());
        if (chatLanguage == null) {
            return new ReadResult(null, issues);
        }
        Map<String, String> env = readEnv(meta, issues, p.fmLines());
        if (env == null) {
            // env 存在非标量键值：整文件拒绝
            return new ReadResult(null, issues);
        }
        BodyResult body = readBody(p, issues);
        if (body == null) {
            return new ReadResult(null, issues);
        }
        String ttsLanguage = optionalString(meta, "ttsLanguage", issues, p.fmLines());
        return new ReadResult(new SelfTalkReuseStore.PrebuiltEntry(entryId, createdAt, env, chatLanguage,
                body.chatText(), ttsLanguage, body.ttsText(), SelfTalkReuseStore.Origin.MACHINE), issues);
    }

    /**
     * 人类条目读取（宽松，见类注释）。{@code derivedEntryId} 由调用方按相对路径派生
     * （{@code UUID.nameUUIDFromBytes(UTF-8(相对路径))}），只在内存里用。
     * 仍必须有 YAML 头与 {@code bodyFormat} 声明；无头、损坏 YAML、未知 bodyFormat
     * 与非法 env 一律拒绝（原文件保留不动）。
     */
    static ReadResult readCustom(String content, UUID derivedEntryId) {
        List<Issue> issues = new ArrayList<>();
        Parse p = parse(content, issues);
        if (p.meta() == null) {
            issues.add(new Issue(1, "custom 条目必须有 YAML 头并声明 bodyFormat: \"" + BODY_FORMAT
                    + "\"；无头或解析失败整条拒绝（原文件保留不变）",
                    "Custom entries must have a YAML header declaring bodyFormat: \"" + BODY_FORMAT
                            + "\"; entries without a header or with an unparsable header are rejected as "
                            + "a whole (the original file is kept unchanged)"));
            return new ReadResult(null, issues);
        }
        if (!requireBodyFormat(p.meta(), issues, p.fmLines())) {
            return new ReadResult(null, issues);
        }
        Map<String, String> env = readEnv(p.meta(), issues, p.fmLines());
        if (env == null) {
            // env 存在非标量键值：整条拒绝（不能丢 key 后继续匹配）
            return new ReadResult(null, issues);
        }
        String chatLanguage = optionalString(p.meta(), "chatLanguage", issues, p.fmLines());
        String ttsLanguage = optionalString(p.meta(), "ttsLanguage", issues, p.fmLines());
        String createdAt = optionalString(p.meta(), "createdAt", issues, p.fmLines());
        BodyResult body = readBody(p, issues);
        if (body == null) {
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
                issues.add(new Issue(1, "YAML 头以 --- 开头但没有结束的 --- 行，整篇按正文处理",
                        "The YAML header starts with --- but has no closing --- line; the whole file is "
                                + "treated as body text"));
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
            Object root = new Yaml(new FencedEnvConstructor(new LoaderOptions())).load(fmText);
            if (root == null) {
                issues.add(new Issue(1, "YAML 头为空", "The YAML header is empty"));
                return Map.of();
            }
            if (!(root instanceof Map<?, ?> map)) {
                issues.add(new Issue(1, "YAML 头必须是键值映射，实际是 " + typeNameZh(root),
                        "The YAML header must be a key-value mapping, but it is " + typeName(root)));
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        } catch (Exception e) {
            issues.add(new Issue(1, "YAML 头解析失败：" + e.getMessage(),
                    "Failed to parse the YAML header: " + e.getMessage()));
            return null;
        }
    }

    /**
     * SafeConstructor 的局部构造分支：仅对顶层 {@code env} 映射改用原始文本构造——标量键值取
     * {@link ScalarNode#getValue()}，保持 {@code yes/on/off}、{@code 1:20}、日期时间等字面值
     * 不被 YAML 1.1 隐式解析改写；其余字段仍走 SafeConstructor 标准解析
     * （{@code schemaVersion} 仍按数值读取）。不改全局 Resolver；显式空值（YAML null）与
     * 复合键值以 Node 原样放入映射，由 {@link #readEnv} 判非法。
     */
    private static final class FencedEnvConstructor extends SafeConstructor {

        private boolean rootDone;

        FencedEnvConstructor(LoaderOptions options) {
            super(options);
        }

        @Override
        protected void constructMapping2ndStep(MappingNode node, Map<Object, Object> mapping) {
            if (rootDone) {
                // 嵌套映射（env 之外的手写字段）仍走标准解析
                super.constructMapping2ndStep(node, mapping);
                return;
            }
            rootDone = true;
            flattenMapping(node);
            for (NodeTuple tuple : node.getValue()) {
                Object key = constructObject(tuple.getKeyNode());
                Node valueNode = tuple.getValueNode();
                if ("env".equals(key) && valueNode instanceof MappingNode envNode) {
                    mapping.put(key, rawScalarMap(envNode));
                } else {
                    mapping.put(key, constructObject(valueNode));
                }
            }
        }

        /** env 映射的原始构造：标量键值取原文，复合键值与空值保留 Node 由 readEnv 拒绝 */
        private static Map<Object, Object> rawScalarMap(MappingNode node) {
            Map<Object, Object> raw = new LinkedHashMap<>();
            for (NodeTuple tuple : node.getValue()) {
                Node keyNode = tuple.getKeyNode();
                Node valueNode = tuple.getValueNode();
                // 用 Tag 值相等判断空值标签：显式 !!null 会给出与 Tag.NULL 等值但不同身份的实例
                Object key = keyNode instanceof ScalarNode keyScalar && !Tag.NULL.equals(keyScalar.getTag())
                        ? keyScalar.getValue() : keyNode;
                Object value = valueNode instanceof ScalarNode valueScalar && !Tag.NULL.equals(valueScalar.getTag())
                        ? valueScalar.getValue() : valueNode;
                raw.put(key, value);
            }
            return raw;
        }
    }

    /**
     * 正文分段（fenced-v1，规则见类注释）：标题只在围栏外识别；每种段最多一个；
     * 重复段、未知段、未闭合围栏、段内缺围栏或段外非空散文一律拒绝——返回 null 并定位告警。
     * 段正文原文保留（仅移除围栏结构的换行，首尾空白不修剪），回退规则在末尾统一应用。
     */
    private static BodyResult readBody(Parse p, List<Issue> issues) {
        List<String> lines = p.bodyLines();
        int absStart = p.bodyStartLine();
        StringBuilder chat = null;
        StringBuilder tts = null;
        boolean chatSeen = false;
        boolean ttsSeen = false;
        // 当前接收正文的缓冲（仅围栏开启后有值）
        StringBuilder current = null;
        // 状态：0 段外（等待标题）/ 1 段内（标题后等待开启围栏）/ 2 围栏内
        int state = 0;
        int fenceLen = 0;
        int fenceOpenLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (state == 2) {
                // 围栏内：只有与开启围栏等长的反引号行才是关闭围栏，
                // 其余（含 H2、缩进、反引号）都是正文原样保留
                if (trimmed.equals("`".repeat(fenceLen))) {
                    state = 0;
                    current = null;
                } else {
                    current.append(raw).append('\n');
                }
                continue;
            }
            if (trimmed.startsWith("## ")) {
                String title = trimmed.substring(3).trim();
                String lower = title.toLowerCase(Locale.ROOT);
                boolean isChat = "chat".equals(lower);
                boolean isTts = "tts".equals(lower);
                if (!isChat && !isTts) {
                    issues.add(new Issue(absStart + i, "未识别的二级标题：## " + title
                            + "，整条拒绝（可用标题：## Chat / ## TTS，大小写不敏感）",
                            "Unrecognized level-2 heading: ## " + title + "; the whole entry is rejected "
                                    + "(allowed headings: ## Chat / ## TTS, case-insensitive)"));
                    return null;
                }
                if (isChat ? chatSeen : ttsSeen) {
                    issues.add(new Issue(absStart + i, "重复的 " + (isChat ? "## Chat" : "## TTS")
                            + " 段（每种段最多一个），整条拒绝",
                            "Duplicate " + (isChat ? "## Chat" : "## TTS")
                                    + " section (at most one section per kind); the whole entry is rejected"));
                    return null;
                }
                if (isChat) {
                    chatSeen = true;
                    chat = new StringBuilder();
                } else {
                    ttsSeen = true;
                    tts = new StringBuilder();
                }
                current = isChat ? chat : tts;
                state = 1;
                continue;
            }
            if (state == 1) {
                // 段内：空行跳过，其余必须是开启围栏
                if (trimmed.isEmpty()) {
                    continue;
                }
                int openLen = parseOpeningFence(trimmed);
                if (openLen > 0) {
                    fenceLen = openLen;
                    fenceOpenLine = absStart + i;
                    state = 2;
                } else if (openLen < 0) {
                    issues.add(new Issue(absStart + i,
                            "围栏标注只支持空或 text（如 ``` 或 ```text），整条拒绝",
                            "The fence info string may only be empty or text (e.g. ``` or ```text); "
                                    + "the whole entry is rejected"));
                    return null;
                } else {
                    issues.add(new Issue(absStart + i,
                            "段正文必须放在反引号围栏内（fenced-v1 格式），整条拒绝",
                            "The section body must be inside a backtick fence (fenced-v1 format); "
                                    + "the whole entry is rejected"));
                    return null;
                }
                continue;
            }
            // state == 0：段外。空行忽略；非空散文一律拒绝，不再忽略内容
            if (!trimmed.isEmpty()) {
                issues.add(new Issue(absStart + i,
                        "标题外的散文本（fenced-v1 只允许 ## Chat / ## TTS 两个围栏段），整条拒绝："
                                + StringUtils.abbreviate(trimmed, 24),
                        "Text outside the headings (fenced-v1 only allows the two fenced sections "
                                + "## Chat / ## TTS); the whole entry is rejected: "
                                + StringUtils.abbreviate(trimmed, 24)));
                return null;
            }
        }
        if (state == 2) {
            issues.add(new Issue(fenceOpenLine, "围栏未闭合（缺少与开启围栏等长的反引号行），整条拒绝",
                    "Unclosed fence (missing a backtick line as long as the opening fence); "
                            + "the whole entry is rejected"));
            return null;
        }
        String chatRaw = sectionText(chat);
        String ttsRaw = sectionText(tts);
        boolean chatUsable = chatRaw != null && !chatRaw.isBlank();
        boolean ttsUsable = ttsRaw != null && !ttsRaw.isBlank();
        // 回退：Chat 缺失/空/全空白且 TTS 有效 → 以 TTS 补 Chat；TTS 无有效内容 → 以 Chat 补 TTS
        String chatText = chatUsable ? chatRaw : ttsRaw;
        String ttsText = ttsUsable ? ttsRaw : chatText;
        if (chatText == null || chatText.isBlank()) {
            issues.add(new Issue(-1, "缺少可用的上屏文本（## Chat / ## TTS 均缺失或全空白），拒绝入池",
                    "No usable on-screen text (both ## Chat and ## TTS are missing or blank); "
                            + "the entry is not added to the pool"));
            return null;
        }
        return new BodyResult(chatText, ttsText);
    }

    /** 段缓冲 → 正文原文：去掉为拼行多出的最后一个换行，其余（含首尾空白）原样保留 */
    private static String sectionText(StringBuilder section) {
        if (section == null) {
            return null;
        }
        String s = section.toString();
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * 开启围栏行（已 trim）：至少 3 个反引号开头，信息串为空或 text。
     * 返回围栏长度；不是围栏行返回 0；围栏标注非法返回 -1。
     */
    private static int parseOpeningFence(String trimmed) {
        if (!trimmed.startsWith("```")) {
            return 0;
        }
        int len = 0;
        while (len < trimmed.length() && trimmed.charAt(len) == '`') {
            len++;
        }
        String info = trimmed.substring(len).trim();
        if (info.isEmpty() || "text".equals(info)) {
            return len;
        }
        return -1;
    }

    /**
     * 解析 env：必须是扁平映射，标量键值取 YAML 原始文本（见 {@link FencedEnvConstructor}）；
     * 显式非映射、复合键、复合值或空值整条拒绝——返回 null，调用方拒绝本条目，
     * 不能丢 key 后继续参与匹配。未知环境 key 保留并记告警（不参与匹配）。
     * <p>
     * 缺省（未写 env 键）与 {@code env: {}} 都是合法通配；显式写空值（{@code env:} /
     * {@code env: null} / {@code env: ~} / {@code env: !!null null}）是非法输入，与缺省区分后整条拒绝。
     */
    private static Map<String, String> readEnv(Map<String, Object> meta, List<Issue> issues,
                                               List<String> fmLines) {
        if (!meta.containsKey("env")) {
            return new LinkedHashMap<>();
        }
        Object raw = meta.get("env");
        if (raw == null) {
            issues.add(new Issue(locateLine(fmLines, "env"),
                    "env 显式为空（YAML null）——无环境约束请删除该行或写成 env: {}，整条拒绝",
                    "env is explicitly empty (YAML null) — remove the line or write env: {} when there is "
                            + "no environment constraint; the whole entry is rejected"));
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            issues.add(new Issue(locateLine(fmLines, "env"),
                    "env 必须是扁平的键值映射，实际是 " + typeNameZh(raw) + "，整条拒绝",
                    "env must be a flat key-value mapping, but it is " + typeName(raw)
                            + "; the whole entry is rejected"));
            return null;
        }
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object keyObj = e.getKey();
            Object value = e.getValue();
            if (!(keyObj instanceof String key)) {
                issues.add(new Issue(locateLine(fmLines, "env"),
                        "env 的 key 必须是标量文本，出现复合 key，整条拒绝",
                        "env keys must be scalar text; a compound key was found and the whole entry "
                                + "is rejected"));
                return null;
            }
            if (!(value instanceof String valueText)) {
                issues.add(new Issue(locateLine(fmLines, key),
                        "env 值必须是标量（字符串/数字/布尔字面值），key " + key
                                + " 的值是复合结构或空值，整条拒绝",
                        "env values must be scalars (string/number/boolean literals); the value of key "
                                + key + " is a compound structure or empty, so the whole entry is rejected"));
                return null;
            }
            if (EnvironmentContextOption.byKey(key) == null) {
                issues.add(new Issue(locateLine(fmLines, key),
                        "未知环境 key：" + key + "（该 key 不参与匹配，请对照报告中的环境 key 目录检查拼写）",
                        "Unknown environment key: " + key + " (it does not participate in matching; "
                                + "check the spelling against the environment key catalog in this report)"));
            }
            env.put(key, valueText);
        }
        return env;
    }

    /** 两类条目共同要求：YAML 头声明 {@code bodyFormat: "fenced-v1"}；缺失或未知拒绝入池 */
    private static boolean requireBodyFormat(Map<String, Object> meta, List<Issue> issues,
                                             List<String> fmLines) {
        String value = optionalString(meta, "bodyFormat", issues, fmLines);
        if (BODY_FORMAT.equals(value)) {
            return true;
        }
        issues.add(new Issue(locateLine(fmLines, "bodyFormat"),
                "bodyFormat 必须是 \"" + BODY_FORMAT + "\"，实际为 " + (value == null ? "缺失" : value)
                        + "；旧格式条目保留原件、停止使用，拒绝入池",
                "bodyFormat must be \"" + BODY_FORMAT + "\" but is "
                        + (value == null ? "missing" : value)
                        + "; old-format entries keep the original file and stop being used, so the entry "
                        + "is not added to the pool"));
        return false;
    }

    /** 值类型名（英文报告用）：null 记为 null，其余为 Java 简单类名 */
    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /** 值类型名（中文报告用）：null 记为「空值」 */
    private static String typeNameZh(Object value) {
        return value == null ? "空值" : typeName(value);
    }

    /** 可选字符串字段：缺失/空白返回 null；非标量记告警后按缺失处理 */
    private static String optionalString(Map<String, Object> meta, String key, List<Issue> issues,
                                         List<String> fmLines) {
        Object value = meta.get(key);
        if (value == null) {
            return null;
        }
        if (!isScalar(value)) {
            issues.add(new Issue(locateLine(fmLines, key), key + " 必须是标量，已按缺失处理",
                    key + " must be a scalar; it is treated as missing"));
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    /** 标量判定：字符串/数字/布尔/字符；null 与 list/map 均非标量 */
    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character;
    }

    /**
     * 机器条目 createdAt 解析：非空标量沿用原值（不校验格式、不修剪、不读文件时间）；
     * 键缺失、YAML 空值或空白字符串视为「缺日期」，用文件最后修改时间补足并记告警；
     * 列表/映射等非标量不属于缺日期，按必填校验拒绝（不调用时间来源）。无时间来源、
     * 时间读取失败或无法转换时记失败告警并返回 null（跳过文件，绝不改用当前时刻）。
     */
    private static String resolveCreatedAt(Map<String, Object> meta, List<Issue> issues,
                                           List<String> fmLines, Supplier<Instant> lastModifiedTime) {
        Object raw = meta.get("createdAt");
        if (raw != null && !isScalar(raw)) {
            issues.add(new Issue(locateLine(fmLines, "createdAt"), "createdAt 必须是标量，拒绝该文件",
                    "createdAt must be a scalar; the file is rejected"));
            return null;
        }
        if (raw != null && !String.valueOf(raw).isBlank()) {
            return String.valueOf(raw);
        }
        if (lastModifiedTime == null) {
            issues.add(new Issue(locateLine(fmLines, "createdAt"), "机器条目缺少必填字段 createdAt，拒绝该文件",
                    "Machine entry is missing the required field createdAt; the file is rejected"));
            return null;
        }
        try {
            Instant fileTime = lastModifiedTime.get();
            if (fileTime == null) {
                issues.add(new Issue(locateLine(fmLines, "createdAt"),
                        "createdAt 缺失或空白，无可用文件最后修改时间，拒绝该文件",
                        "createdAt is missing or blank and no file last-modified time is available; "
                                + "the file is rejected"));
                return null;
            }
            String backfilled = formatCreatedAt(fileTime);
            issues.add(new Issue(locateLine(fmLines, "createdAt"),
                    "createdAt 缺失或空白，已使用文件最后修改时间补足：" + backfilled,
                    "createdAt is missing or blank; it was filled from the file last-modified time: "
                            + backfilled));
            return backfilled;
        } catch (RuntimeException e) {
            issues.add(new Issue(locateLine(fmLines, "createdAt"),
                    "createdAt 缺失或空白，文件最后修改时间不可用：" + e + "，拒绝该文件",
                    "createdAt is missing or blank and the file last-modified time is unavailable: "
                            + e + "; the file is rejected"));
            return null;
        }
    }

    /** 机器条目必填字符串字段：缺失/空白记告警并返回 null（调用方拒绝文件） */
    private static String requireString(Map<String, Object> meta, String key, List<Issue> issues,
                                        List<String> fmLines) {
        String s = optionalString(meta, key, issues, fmLines);
        if (s == null) {
            issues.add(new Issue(locateLine(fmLines, key), "机器条目缺少必填字段 " + key + "，拒绝该文件",
                    "Machine entry is missing the required field " + key + "; the file is rejected"));
        }
        return s;
    }

    /** 解析机器条目 entryId：缺失或不是合法 UUID 记告警并返回 null */
    private static UUID parseEntryId(Map<String, Object> meta, List<Issue> issues, List<String> fmLines) {
        String raw = optionalString(meta, "entryId", issues, fmLines);
        if (raw == null) {
            issues.add(new Issue(locateLine(fmLines, "entryId"), "机器条目缺少合法 entryId，拒绝该文件",
                    "Machine entry has no valid entryId; the file is rejected"));
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            issues.add(new Issue(locateLine(fmLines, "entryId"), "entryId 不是合法 UUID：" + raw + "，拒绝该文件",
                    "entryId is not a valid UUID: " + raw + "; the file is rejected"));
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

    private static final char UTF8_BOM = '﻿';

    private static String stripBom(String content) {
        if (!content.isEmpty() && content.charAt(0) == UTF8_BOM) {
            return content.substring(1);
        }
        return content;
    }
}
