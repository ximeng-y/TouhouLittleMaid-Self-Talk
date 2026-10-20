package com.maidmod.selftalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code custom/} 扫描诊断：按文件聚合告警，覆盖写出 {@code custom/_diagnostics.md}
 * （每次扫描覆盖生成、不累积；扫描侧与报告写出侧都忽略该文件自身），控制台只打一行汇总。
 * <p>
 * 报告内容：生成时间与问题总数；「未匹配到女仆的目录」单列；每个有告警的文件一个
 * {@code ##} 标题（相对路径），下用 {@code - } 列表逐条带行号说明；末尾附当前可识别的
 * 全部环境 key 目录（key 与服务端可得的提供者名称）与正文分段规则说明。
 * {@code _global} 的告警只进本报告与控制台汇总，不点名任何主人（私信通知由
 * {@link PrebuiltCustomEntries#onPlayerLogin} 只针对具名目录处理）。
 * <p>
 * 文本语言跟随 {@link Config#SELF_TALK_LANGUAGE}（自话输出语言配置）：中文标签写中文报告，
 * 其余合法语言写英文报告；文件路径、段落结构与环境 key 目录不变。告警正文由
 * {@link PrebuiltEntryCodec.Issue} 同时提供中英两套文案。
 */
final class PrebuiltDiagnostics {

    /** 诊断报告文件名（位于 custom/ 根，扫描时忽略，避免递归） */
    static final String DIAGNOSTICS_FILE = "_diagnostics.md";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss");

    private final Path customDir;
    /** 相对路径 → 告警列表（保持扫描顺序） */
    private final Map<String, List<PrebuiltEntryCodec.Issue>> byFile = new LinkedHashMap<>();
    /** 有文件但当前已加载女仆都匹配不上的具名目录 */
    private final List<String> unmatchedDirs = new ArrayList<>();

    PrebuiltDiagnostics(Path customDir) {
        this.customDir = customDir;
    }

    /**
     * 诊断文本语言：中文标签写中文、其余合法语言写英文。
     * <p>
     * 与其他游戏内语言判定同一入口：先经 {@link SelfTalkContexts#sanitizeLanguage} 校验（非法或缺失
     * 回退 {@code zh_cn}），再由 {@link ContextLanguage} 取模板语言，不在这里另拼
     * {@code startsWith("zh")}。只在服务端运行期（扫描与停服重写）调用，此时配置已装载；
     * 配置字段尚未注入（为 null）时按默认中文处理。
     */
    static boolean chinese() {
        var configured = Config.SELF_TALK_LANGUAGE;
        return ContextLanguage.of(SelfTalkContexts.sanitizeLanguage(
                configured == null ? null : configured.get())).isZh();
    }

    /** 记录一个文件的告警（issues 为空时不记） */
    void recordFile(String relPath, List<PrebuiltEntryCodec.Issue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        byFile.computeIfAbsent(relPath, k -> new ArrayList<>()).addAll(issues);
    }

    void recordUnmatchedDir(String name) {
        unmatchedDirs.add(name);
    }

    /**
     * 覆盖写出报告；custom/ 不存在时不写、不创建目录。{@code logSummary} 为 true 时
     * 控制台打一行汇总（扫描完成时）；停服重写为幂等覆盖，不再刷屏。
     * <p>
     * 报告语言取 {@link #chinese()}（配置可热重载，下一次扫描/停服重写即按新语言）。
     */
    void writeReport(boolean logSummary) {
        writeReport(logSummary, chinese());
    }

    /**
     * 按指定语言覆盖写出；{@code chinese} 为 true 写中文报告、false 写英文报告。
     * 停服重写与脱离配置的自检可直接指定语言。
     */
    void writeReport(boolean logSummary, boolean chinese) {
        if (!Files.isDirectory(customDir)) {
            return;
        }
        int total = byFile.values().stream().mapToInt(List::size).sum();
        StringBuilder sb = new StringBuilder();
        sb.append(chinese ? "# 预制复用自定义条目诊断报告" : "# Prebuilt reuse custom entry diagnostics")
                .append("\n\n");
        sb.append("- ").append(chinese ? "生成时间：" : "Generated at: ")
                .append(LocalDateTime.now().format(TIME_FORMAT)).append('\n');
        sb.append("- ").append(chinese ? "问题总数：" : "Total issues: ").append(total).append('\n');
        sb.append("- ").append(chinese
                ? "本报告每次扫描覆盖生成；修复文件后重启服务器重新扫描。"
                : "This report is overwritten on every scan; restart the server to rescan after fixing the files.")
                .append("\n\n");
        if (!unmatchedDirs.isEmpty()) {
            sb.append(chinese ? "## 未匹配到女仆的目录" : "## Directories matching no maid").append("\n\n");
            for (String dir : unmatchedDirs) {
                sb.append("- `").append(dir).append(chinese
                        ? "/`：目录名与当前已加载女仆的解析名称均不一致（命名牌名称优先，其次内置模型预置名且跟随该女仆的聊天语言；改名后需手动把目录改成新名字）"
                        : "/`: the directory name matches no loaded maid's resolved name (name tag first, "
                        + "otherwise the built-in model preset name, which follows that maid's chat language; "
                        + "after renaming a maid, rename the directory manually)")
                        .append('\n');
            }
            sb.append('\n');
        }
        for (Map.Entry<String, List<PrebuiltEntryCodec.Issue>> entry : byFile.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n\n");
            for (PrebuiltEntryCodec.Issue issue : entry.getValue()) {
                sb.append("- ")
                        .append(issue.line() > 0
                                ? (chinese ? "第 " + issue.line() + " 行：" : "Line " + issue.line() + ": ")
                                : "")
                        .append(issue.message(chinese)).append('\n');
            }
            sb.append('\n');
        }
        sb.append(chinese ? "## 可用环境 key 目录" : "## Available environment keys").append("\n\n");
        sb.append(chinese
                ? "frontmatter 里只能写以下 key（值须与当轮环境严格相等才命中；没写的 key 不约束）："
                : "Only the keys below may be written in the frontmatter (a written key must equal that "
                + "round's value exactly to match; omitted keys impose no constraint):")
                .append("\n\n");
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            String label = EnvironmentContextSettings.labelOf(option.key());
            sb.append("- `").append(option.key()).append('`');
            if (label != null) {
                sb.append(chinese ? "（" : " (").append(label).append(chinese ? "）" : ")");
            }
            sb.append('\n');
        }
        sb.append('\n').append(chinese
                ? "## 正文格式（fenced-v1）与回退规则"
                : "## Body format (fenced-v1) and fallback rules").append("\n\n");
        sb.append(chinese
                ? "条目必须有 YAML 头并声明 `bodyFormat: \"fenced-v1\"`，正文用反引号围栏包裹。"
                + "最小完整样例（缩进仅为展示，实际写时不要缩进）：\n\n"
                : "An entry must have a YAML header declaring `bodyFormat: \"fenced-v1\"` and wrap its "
                + "body in backtick fences. Minimal complete example (indentation is only for display; "
                + "do not indent when writing):\n\n");
        sb.append("    ---\n");
        sb.append("    bodyFormat: \"fenced-v1\"\n");
        sb.append("    chatLanguage: \"").append(chinese ? "zh_cn" : "en_us").append("\"\n");
        sb.append("    env:\n");
        sb.append("      weather: \"Sunny\"\n");
        sb.append("    ---\n\n");
        sb.append("    ## Chat\n\n");
        sb.append("    ```\n");
        sb.append(chinese ? "    （这里写上屏文本）\n" : "    (on-screen text goes here)\n");
        sb.append("    ```\n\n");
        sb.append("    ## TTS\n\n");
        sb.append("    ```\n");
        sb.append(chinese
                ? "    （这里写语音文本；整段可省略）\n"
                : "    (voice text goes here; the whole section may be omitted)\n");
        sb.append("    ```\n\n");
        sb.append("- ").append(chinese
                ? "开启围栏至少 3 个反引号，且比正文里最长的连续反引号更长；围栏后可留空或写 text，"
                + "关闭围栏须与开启围栏等长。"
                : "The opening fence needs at least 3 backticks and must be longer than the longest "
                + "backtick run in the body; the fence may be followed by nothing or by text, and the "
                + "closing fence must be as long as the opening fence.")
                .append('\n');
        sb.append("- ").append(chinese
                ? "标题只在围栏外识别；围栏内的二级标题、缩进与反引号都是正文。每种段最多一个："
                + "重复段、未知二级标题、未闭合围栏、围栏外的非空文字都会整条拒绝。"
                : "Headings are recognized only outside fences; level-2 headings, indentation and "
                + "backticks inside a fence are all body text. At most one section per kind: duplicate "
                + "sections, unknown level-2 headings, unclosed fences and non-empty text outside fences "
                + "reject the whole entry.")
                .append('\n');
        sb.append("- ").append(chinese
                ? "上屏文本与语音文本原文保留（首尾空白不裁剪）。`## Chat` 缺失、空或全空白且 "
                + "`## TTS` 有效时，上屏文本用 TTS 文本；`## TTS` 缺失或全空白时，语音文本用上屏文本；"
                + "两者都为空整条拒绝。"
                : "On-screen and voice text are kept verbatim (leading and trailing whitespace is not "
                + "trimmed). When `## Chat` is missing, empty or blank while `## TTS` is valid, the "
                + "on-screen text uses the TTS text; when `## TTS` is missing or blank, the voice text "
                + "uses the on-screen text; the entry is rejected only when both are empty.")
                .append('\n');
        sb.append("- ").append(chinese
                ? "`env` 必须是扁平键值映射：显式写成列表/映射、复合键、复合值或空值会整条拒绝"
                + "（不会丢掉部分条件继续匹配）；无环境约束请省略 `env` 或写 `env: {}`，"
                + "不要写 `env:` / `env: null`；未加引号的 yes/on/off、时间（如 1:20）、日期"
                + "按字面值参与匹配；未知 key 保留并告警、不参与匹配。"
                : "`env` must be a flat key-value mapping: explicitly writing a list/map, a compound key, "
                + "a compound value or an empty value rejects the whole entry (matching never continues "
                + "with only part of the conditions); omit `env` or write `env: {}` when there is no "
                + "environment constraint, and do not write `env:` / `env: null`; unquoted yes/on/off, "
                + "times (e.g. 1:20) and dates are matched as literal values; unknown keys are kept with "
                + "a warning and do not participate in matching.")
                .append('\n');
        sb.append("- ").append(chinese
                ? "旧格式（无头、旧 schema 或缺少 bodyFormat）条目不会删除或改写，只是停止参与匹配；"
                + "按上面的样例改写成新格式即可恢复。"
                : "Old-format entries (no header, old schema or missing bodyFormat) are neither deleted "
                + "nor rewritten; they simply stop participating in matching. Rewrite them in the format "
                + "shown above to bring them back.")
                .append('\n');
        if (write(sb.toString()) && logSummary) {
            MaidSelfTalkMod.LOGGER.info("Prebuilt reuse custom entries: {} file(s) with {} issue(s), report: {}",
                    byFile.size(), total, customDir.resolve(DIAGNOSTICS_FILE));
        }
    }

    /** 覆盖写出（临时文件 + 原子替换）；失败记警告返回 false */
    private boolean write(String report) {
        Path file = customDir.resolve(DIAGNOSTICS_FILE);
        try {
            Path tmp = customDir.resolve(file.getFileName() + ".tmp");
            Files.writeString(tmp, report, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            MaidSelfTalkMod.LOGGER.warn("Failed to write prebuilt custom diagnostics report {}: {}",
                    file, e.toString());
            return false;
        }
    }
}
