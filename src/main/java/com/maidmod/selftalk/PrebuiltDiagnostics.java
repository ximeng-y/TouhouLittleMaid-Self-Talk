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
     */
    void writeReport(boolean logSummary) {
        if (!Files.isDirectory(customDir)) {
            return;
        }
        int total = byFile.values().stream().mapToInt(List::size).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("# 预制复用自定义条目诊断报告\n\n");
        sb.append("- 生成时间：").append(LocalDateTime.now().format(TIME_FORMAT)).append('\n');
        sb.append("- 问题总数：").append(total).append('\n');
        sb.append("- 本报告每次扫描覆盖生成；修复文件后重启服务器重新扫描。\n\n");
        if (!unmatchedDirs.isEmpty()) {
            sb.append("## 未匹配到女仆的目录\n\n");
            for (String dir : unmatchedDirs) {
                sb.append("- `").append(dir).append("/`：目录名与当前已加载女仆的解析名称均不一致")
                        .append("（命名牌名称优先，其次内置模型预置名且跟随该女仆的聊天语言；")
                        .append("改名后需手动把目录改成新名字）\n");
            }
            sb.append('\n');
        }
        for (Map.Entry<String, List<PrebuiltEntryCodec.Issue>> entry : byFile.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n\n");
            for (PrebuiltEntryCodec.Issue issue : entry.getValue()) {
                sb.append("- ").append(issue.line() > 0 ? "第 " + issue.line() + " 行：" : "")
                        .append(issue.message()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("## 可用环境 key 目录\n\n");
        sb.append("frontmatter 里只能写以下 key（值须与当轮环境严格相等才命中；没写的 key 不约束）：\n\n");
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            String label = EnvironmentContextSettings.labelOf(option.key());
            sb.append("- `").append(option.key()).append('`');
            if (label != null) {
                sb.append("（").append(label).append("）");
            }
            sb.append('\n');
        }
        sb.append("\n## 正文分段规则\n\n");
        sb.append("- `## Chat` 段为上屏文本，`## TTS` 段为语音文本；标题大小写不敏感，")
                .append("必须以 ## 加空格开头独占一行。\n");
        sb.append("- 没有任何已识别标题时，全文作为上屏文本，语音文本回退为同文；")
                .append("只有 TTS 段时 TTS 文本同时作为上屏文本。\n");
        sb.append("- 未识别的二级标题整段忽略；frontmatter 之后、首个标题之前的散文本忽略。\n");
        sb.append("- 无头条目视为通用条目：不写 chatLanguage 对所有语言生效，env 为空则不做环境约束。\n");
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
