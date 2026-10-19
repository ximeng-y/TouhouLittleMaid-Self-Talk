package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Stream;

/**
 * {@code custom/} 人类可编辑条目库（实验性，随预制复用开关生效）：手写 Markdown 条目的
 * 扫描、作用域索引与只读语义。目录结构：
 * <pre>
 * &lt;世界根&gt;/data/maid_self_talk/prebuilt/custom/
 *   _global/{welcome,self-talk}/*.md    # 对所有女仆生效
 *   &lt;女仆显示名&gt;/{welcome,self-talk}/*.md  # 仅对名称完全匹配的女仆生效
 *   _diagnostics.md                     # 自动生成的诊断报告（扫描时忽略）
 * </pre>
 * <p>
 * 行为边界：
 * <ul>
 *   <li>惰性扫描：本次服务器会话首次访问（首次匹配或首次主人上线通知检查）时扫描一次，
 *       运行中手改文件不生效，重启服务器生效——与机器条目的惰性加载语义一致；</li>
 *   <li>只读：不参与容量淘汰、不写回任何文件、TTS 翻译结果只在内存里用（不回写）；
 *       {@code entryId} 由 UTF-8 相对路径（{@code /} 分隔）经 {@code UUID.nameUUIDFromBytes}
 *       派生，只在内存里用，磁盘上保持人类起的名字，改名即换一条新条目；</li>
 *   <li>作用域：{@code _global} 为保留的全局作用域；其余下划线开头的顶层目录是 mod 保留名，
 *       忽略；具名目录按 {@link SelfTalkMaidNames#resolveName} 的解析结果匹配（命名牌优先、
 *       内置模型预置名跟随该女仆的聊天语言），不绑定 UUID 或指纹，同名女仆共享同一目录；</li>
 *   <li>只扫描来源目录顶层的 {@code .md} 文件（不递归子目录）；{@code _diagnostics.md}
 *       永远不作为条目读取；</li>
 *   <li>扫描期间把每个文件的告警交给 {@link PrebuiltDiagnostics}，并在扫描完成后覆盖写出报告。</li>
 * </ul>
 * 全部方法仅服务端主线程调用，无需加锁；实例表加同步仅为防跨线程查询。
 */
final class PrebuiltCustomEntries {

    /** 全局作用域目录名（下划线开头的保留名里唯一有语义的一个） */
    static final String GLOBAL_SCOPE = "_global";

    private static final Map<MinecraftServer, PrebuiltCustomEntries> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Path customDir;
    /** 作用域（null = 全局，其他 = 女仆显示名）→ 来源 → 条目列表 */
    private final Map<String, Map<String, List<SelfTalkReuseStore.PrebuiltEntry>>> byScope =
            new LinkedHashMap<>();
    /** 条目 UUID（由相对路径派生）→ 源 Markdown 相对路径（/ 分隔）：自动音频镜像目录定位用 */
    private final Map<UUID, String> audioSourceRelPaths = new LinkedHashMap<>();
    /** 有告警的具名目录 → 告警文件数（主人上线通知用；_global 不进这里，不点名主人） */
    private final Map<String, Integer> warnedNamedDirs = new LinkedHashMap<>();
    /** 本会话已收到告警私信的玩家：登出不重置，停服随实例释放 */
    private final Set<UUID> notifiedPlayers = new HashSet<>();
    private final PrebuiltDiagnostics diagnostics;

    private PrebuiltCustomEntries(Path customDir, PrebuiltDiagnostics diagnostics) {
        this.customDir = customDir;
        this.diagnostics = diagnostics;
    }

    /** 取（或惰性扫描）某服务端实例的 custom/ 库；实例由 {@link #release} 在服务器停止时移除 */
    static PrebuiltCustomEntries of(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, PrebuiltCustomEntries::scan);
    }

    /** 服务器停止：强制写出最终诊断报告（幂等覆盖）并释放本会话全部内存状态 */
    static void release(MinecraftServer server) {
        PrebuiltCustomEntries entries = INSTANCES.remove(server);
        if (entries != null) {
            entries.diagnostics.writeReport(false);
        }
    }

    /**
     * 全局 + 具名（名称完全相等）两条来源的候选合并输出；{@code maidName} 为 null
     * （命名牌与内置模型名都解析不出的女仆）时只有全局条目参与。
     */
    List<SelfTalkReuseStore.PrebuiltEntry> entriesFor(String maidName, String source) {
        List<SelfTalkReuseStore.PrebuiltEntry> out = new ArrayList<>();
        Map<String, List<SelfTalkReuseStore.PrebuiltEntry>> global = byScope.get(null);
        if (global != null) {
            out.addAll(global.getOrDefault(source, List.of()));
        }
        if (maidName != null) {
            Map<String, List<SelfTalkReuseStore.PrebuiltEntry>> named = byScope.get(maidName);
            if (named != null) {
                out.addAll(named.getOrDefault(source, List.of()));
            }
        }
        return out;
    }

    /**
     * 主人上线（服务端主线程）：按当前已加载、有主人且名称匹配的女仆，找其具名目录的告警；
     * 每个玩家每个服务器会话只发一条合并私信（首次有告警时），登出不重置、停服清空。
     * {@code _global} 的告警不点名任何人，只进报告与控制台。首次访问会触发惰性扫描
     * （custom/ 不存在时按空库处理，不创建目录）。
     */
    void onPlayerLogin(ServerPlayer player) {
        if (notifiedPlayers.contains(player.getUUID()) || warnedNamedDirs.isEmpty()) {
            return;
        }
        UUID owner = player.getUUID();
        int total = 0;
        for (ServerLevel level : player.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof EntityMaid maid) || !maid.isAlive() || maid.isRemoved()) {
                    continue;
                }
                if (!owner.equals(maid.getOwnerUUID())) {
                    continue;
                }
                Component name = SelfTalkMaidNames.resolveName(maid, SelfTalkMaidNames.resolveQueryLanguage(maid));
                if (name != null) {
                    total += warnedNamedDirs.getOrDefault(name.getString(), 0);
                }
            }
        }
        if (total > 0) {
            player.sendSystemMessage(Component.translatable(
                    "chat.maid_self_talk.prebuilt_custom_warnings", total));
            notifiedPlayers.add(player.getUUID());
        }
    }

    // ===== 扫描 =====

    private static PrebuiltCustomEntries scan(MinecraftServer server) {
        Path customDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("maid_self_talk").resolve("prebuilt")
                .resolve(SelfTalkReuseStore.CUSTOM_DIR_NAME);
        PrebuiltDiagnostics diagnostics = new PrebuiltDiagnostics(customDir);
        PrebuiltCustomEntries result = new PrebuiltCustomEntries(customDir, diagnostics);
        if (!Files.isDirectory(customDir)) {
            // 目录不存在：空库。不创建目录、不写报告，首次成功扫描到内容时才落盘
            return result;
        }
        List<Path> topLevel;
        try (Stream<Path> stream = Files.list(customDir)) {
            topLevel = stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            MaidSelfTalkMod.LOGGER.warn("Prebuilt reuse custom dir {} cannot be listed ({}), "
                    + "treated as empty for this run", customDir, e.toString());
            return result;
        }
        Set<String> loadedMaidNames = collectLoadedMaidNames(server);
        for (Path scopeDir : topLevel) {
            String scope = scopeDir.getFileName().toString();
            boolean global = GLOBAL_SCOPE.equals(scope);
            if (!global && scope.startsWith("_")) {
                // 下划线开头的顶层目录是 mod 保留名（_global 除外），忽略
                continue;
            }
            boolean hasContent = false;
            int warnedFiles = 0;
            for (String source : List.of(SelfTalkReuseStore.SOURCE_WELCOME,
                    SelfTalkReuseStore.SOURCE_SELF_TALK)) {
                Path sourceDir = scopeDir.resolve(source);
                if (!Files.isDirectory(sourceDir)) {
                    continue;
                }
                ScopeScan scanned = scanSourceDir(result, diagnostics, customDir, scope, source, sourceDir);
                hasContent |= scanned.hasContent();
                warnedFiles += scanned.warnedFiles();
            }
            // 具名目录放了一些文件但当前已加载女仆都匹配不上：单列进报告（不点名主人）
            if (!global && hasContent && !loadedMaidNames.contains(scope)) {
                diagnostics.recordUnmatchedDir(scope);
            }
            // 具名目录有告警文件：登记待主人上线时合并私信（_global 不点名主人）
            if (!global && warnedFiles > 0) {
                result.warnedNamedDirs.put(scope, warnedFiles);
            }
        }
        diagnostics.writeReport(true);
        return result;
    }

    /** 单个「作用域/来源」目录的扫描结果：hasContent 用于未匹配目录判定，warnedFiles 供登录通知计数 */
    private record ScopeScan(boolean hasContent, int warnedFiles) {
    }

    /**
     * 扫描单个「作用域/来源」目录顶层的 {@code .md} 文件（不递归）。
     * 同目录下仅大小写不同的文件名视为 Windows 冲突：后一个跳过并告警。
     */
    private static ScopeScan scanSourceDir(PrebuiltCustomEntries result, PrebuiltDiagnostics diagnostics,
                                           Path customDir, String scope, String source, Path sourceDir) {
        List<Path> files;
        try (Stream<Path> stream = Files.list(sourceDir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            MaidSelfTalkMod.LOGGER.warn("Prebuilt reuse custom source dir {} cannot be listed ({}), skipped",
                    sourceDir, e.toString());
            return new ScopeScan(false, 0);
        }
        boolean hasContent = false;
        int warnedFiles = 0;
        Map<String, String> seenByLowerName = new LinkedHashMap<>();
        for (Path file : files) {
            String relPath = relPathOf(customDir, file);
            String name = file.getFileName().toString();
            if (PrebuiltDiagnostics.DIAGNOSTICS_FILE.equalsIgnoreCase(name)) {
                // 诊断报告文件永远不会是条目（正常不在来源目录里，防手挪）
                continue;
            }
            String prior = seenByLowerName.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            if (prior != null) {
                diagnostics.recordFile(relPath, List.of(new PrebuiltEntryCodec.Issue(-1,
                        "与文件 " + prior + " 仅大小写不同（Windows 下二者是同一文件），本文件跳过")));
                hasContent = true;
                warnedFiles++;
                continue;
            }
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | UncheckedIOException e) {
                diagnostics.recordFile(relPath, List.of(new PrebuiltEntryCodec.Issue(-1,
                        "文件读取失败：" + e)));
                hasContent = true;
                warnedFiles++;
                continue;
            }
            UUID derivedId = UUID.nameUUIDFromBytes(relPath.getBytes(StandardCharsets.UTF_8));
            PrebuiltEntryCodec.ReadResult read = PrebuiltEntryCodec.readCustom(content, derivedId);
            if (!read.issues().isEmpty()) {
                diagnostics.recordFile(relPath, read.issues());
                hasContent = true;
                warnedFiles++;
            }
            if (read.entry() != null) {
                result.add(scope, source, read.entry());
                result.audioSourceRelPaths.put(derivedId, relPath);
                hasContent = true;
            }
        }
        return new ScopeScan(hasContent, warnedFiles);
    }

    private void add(String scope, String source, SelfTalkReuseStore.PrebuiltEntry entry) {
        // 作用域键：null = 全局，其他 = 女仆显示名（名称完全相等才算命中）
        String scopeKey = GLOBAL_SCOPE.equals(scope) ? null : scope;
        byScope.computeIfAbsent(scopeKey, k -> new LinkedHashMap<>())
                .computeIfAbsent(source, k -> new ArrayList<>())
                .add(entry);
    }

    /**
     * 当前全部已加载女仆的解析名称集合（命名牌优先、内置模型预置名跟随各自聊天语言）。
     * 区块未加载的女仆不计入——它们此刻本就匹配不到任何触发。
     */
    private static Set<String> collectLoadedMaidNames(MinecraftServer server) {
        Set<String> names = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof EntityMaid maid) || !maid.isAlive() || maid.isRemoved()) {
                    continue;
                }
                Component name = SelfTalkMaidNames.resolveName(maid, SelfTalkMaidNames.resolveQueryLanguage(maid));
                if (name != null) {
                    names.add(name.getString());
                }
            }
        }
        return names;
    }

    /** custom/ 内相对路径，统一 {@code /} 分隔（entryId 派生与诊断报告的跨平台稳定键） */
    private static String relPathOf(Path customDir, Path file) {
        StringBuilder sb = new StringBuilder();
        for (Path part : customDir.relativize(file)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part.getFileName().toString());
        }
        return sb.toString();
    }

    /**
     * custom 条目源 Markdown 相对路径（{@code /} 分隔，如 {@code <scope>/<source>/<file>.md}），
     * 预制音频缓存定位同目录人工音频与自动音频镜像目录用；非 custom 条目或本会话未扫描到
     * 该条目返回 null。惰性触发扫描，与匹配入口同一实例。
     */
    static String customSourceRelPath(MinecraftServer server, UUID entryId) {
        return of(server).audioSourceRelPaths.get(entryId);
    }
}
