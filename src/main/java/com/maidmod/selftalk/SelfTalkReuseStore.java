package com.maidmod.selftalk;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 预制复用机器条目的世界级 YAML 库：每个身份指纹一个目录，目录下按来源分 {@code welcome/}
 * 与 {@code self-talk/} 两个子目录，每个条目单独存一个 Markdown 文件（编解码见
 * {@link PrebuiltEntryCodec}）。人类可编辑条目（{@code custom/}）不经过本类，见
 * {@link PrebuiltCustomEntries}。
 * <p>
 * 存储位置 {@code <世界根>/data/maid_self_talk/prebuilt/generated/<source>/v1-<fingerprint>/entry-<UUID>.md}——
 * {@code generated/} 一层区分「mod 自动生成」与「人手写」；{@code v1-} 是身份指纹规则版本前缀；
 * 来源由所在子目录表达、条目内不重复记；{@code entryId} 是存储条目自身的 UUID（与文件名一致），
 * 不是女仆 UUID。不随女仆 NBT、不随玩家设置走，同服同世界共享；换世界即换库，不做跨世界同步，
 * 也不下发客户端。旧版平铺路径 {@code prebuilt/v1-<fingerprint>/…}（JSON v2）与更旧的扁平文件
 * {@code prebuilt/<fingerprint>.json}（schemaVersion 1）完全不兼容：不读取、不迁移、也不删除，
 * 原样留在磁盘上。
 * <p>
 * 磁盘生命周期（刻意最小化）：
 * <ul>
 *   <li>内存按服务端实例隔离（{@link #of}），首次访问某指纹某来源才扫描对应子目录
 *       （惰性加载，只看该目录顶层的 {@code *.md}，不递归）；子目录不存在按空库处理，
 *       不主动创建，首次成功写入时再建目录；</li>
 *   <li>写入只置脏，主线程不落盘——脏条目统一在 overworld 存档事件
 *       （{@code LevelEvent.Save}）与服务器停止（{@code ServerStoppedEvent} → {@link #release}）时写出；</li>
 *   <li>每个条目文件单独走「同目录临时文件 + 原子替换」写出；写入边界的 I/O 与预期运行时
 *       故障都转为单条失败并保留原文件与脏标记，下轮存档重试，其余条目继续写；
 *       容量淘汰的条目文件先等本轮全部新增/更新写成功后再删除，
 *       避免新增写失败时把已有条目也丢了；</li>
 *   <li>单个 Markdown 损坏、旧格式、schema 不识别、字段错误或文件名与 {@code entryId}
 *       不一致：跳过该文件、保留原文件并记诊断日志，同目录其余合法条目照常参与匹配；</li>
 *   <li>整个来源子目录无法枚举时，该来源本次运行锁为只读（坏档闩锁）：
 *       读取按空库、追加与 TTS 覆写直接放弃，绝不把「读不出来的已有库存」当空库覆盖。</li>
 * </ul>
 * 扫描加载合法条目后按当前容量重算裁剪（沿用与追加相同的淘汰排序），容量调小后无需
 * 等下一次新增即可收缩。全部方法仅服务端主线程调用（写入在真实回复入库时、读取在触发匹配时，
 * 落盘在存档事件里），无需加锁；静态表加同步仅为防跨线程查询。
 */
public final class SelfTalkReuseStore {

    /** 欢迎语来源名（同时是其子目录名，来源由所在目录表达，条目内不重复记来源） */
    public static final String SOURCE_WELCOME = "welcome";
    /** 自话来源名（同时是其子目录名） */
    public static final String SOURCE_SELF_TALK = "self-talk";

    /** 机器条目总目录（与人类可编辑的 {@code custom/} 成对命名，见 {@link PrebuiltCustomEntries}） */
    static final String GENERATED_DIR = "generated";
    /** 人类可编辑条目总目录（扫描见 {@link PrebuiltCustomEntries}，本类不触碰） */
    static final String CUSTOM_DIR_NAME = "custom";
    /** 身份指纹规则版本前缀：v1 = 「女仆名称 + 当前生效人设全文」的 SHA-256 规则 */
    private static final String FINGERPRINT_DIR_PREFIX = "v1-";
    /** 指纹段必须是本 mod 自己产出的 64 位小写十六进制，杜绝任意字符串拼进路径 */
    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("[0-9a-f]{64}");
    /** 追加入库的 UUID 冲突重试上限：首次候选 + 最多两次重新随机，总共最多尝试 3 个 UUID */
    private static final int APPEND_UUID_MAX_ATTEMPTS = 3;

    /** 实例表按服务端弱引用持有，服务器停止时由 {@link #release} 显式写出并移除 */
    private static final Map<MinecraftServer, SelfTalkReuseStore> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Path dir;
    /** 扫描重算容量时现读的容量来源（生产为配置；自检注入固定值） */
    private final IntSupplier scanCapacity;
    /** 淘汰条目文件删除操作：(来源目录, entryId) → 是否删除成功（生产为真实文件操作；自检注入故障） */
    private final BiPredicate<Path, UUID> deleteOperation;
    /** fingerprint → 来源 → 该来源子目录的内存态（只缓存 v1 新版库；旧版扁平文件不进内存） */
    private final Map<String, Map<String, SourceEntries>> fingerprints = new LinkedHashMap<>();

    /** 生产构造：库路径取服务端世界目录，扫描容量现读配置，删除走真实文件操作 */
    private SelfTalkReuseStore(MinecraftServer server) {
        this(server.getWorldPath(LevelResource.ROOT)
                        .resolve("data").resolve("maid_self_talk").resolve("prebuilt"),
                () -> Config.PREBUILT_MAX_ENTRIES_PER_SOURCE.get(),
                SelfTalkReuseStore::deleteEntryFile);
    }

    /**
     * 包内自检构造：注入库路径、扫描容量来源与删除操作，用于确定性自检复现删除故障、
     * 容量重算等状态，不依赖系统权限变更或真实文件锁；不注册进实例表。
     */
    SelfTalkReuseStore(Path prebuiltDir, IntSupplier scanCapacity, BiPredicate<Path, UUID> deleteOperation) {
        this.dir = prebuiltDir;
        this.scanCapacity = scanCapacity;
        this.deleteOperation = deleteOperation;
    }

    /** 取（或创建）某服务端实例的内存库；实例由 {@link #release} 在服务器停止时移除 */
    public static SelfTalkReuseStore of(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, SelfTalkReuseStore::new);
    }

    /** 服务器停止：先写出脏条目文件，再释放该实例的全部内存缓存 */
    public static void release(MinecraftServer server) {
        SelfTalkReuseStore store = INSTANCES.remove(server);
        if (store != null) {
            store.saveDirty();
        }
    }

    /** 把全部脏条目文件写盘（overworld 存档事件与服务器停止时调用） */
    public void saveDirty() {
        for (Map<String, SourceEntries> sources : fingerprints.values()) {
            for (SourceEntries entries : sources.values()) {
                saveSourceDirty(entries);
            }
        }
    }

    /**
     * 写出单个来源子目录的脏数据：先写全部新增/更新文件，全部成功后才删除淘汰条目文件。
     * 任一步失败都保留脏状态，下一次存档或停服重试；目录被锁为只读的来源直接跳过。
     */
    private void saveSourceDirty(SourceEntries entries) {
        if (entries.unreadable || (entries.dirtyIds.isEmpty() && entries.removedIds.isEmpty())) {
            return;
        }
        boolean allWritten = true;
        for (UUID id : entries.dirtyIds) {
            PrebuiltEntry entry = entries.entries.get(id);
            // 置脏后被淘汰的条目不再写文件——它已不在库中，其文件删除在下方统一处理
            if (entry == null) {
                continue;
            }
            if (!writeEntryFile(entries.dir, entry)) {
                allWritten = false;
            }
        }
        boolean allDeleted = false;
        // 先确保新增/更新文件全部落盘，再动淘汰清单：写失败时旧条目文件一个都不删
        if (allWritten) {
            allDeleted = true;
            for (UUID id : entries.removedIds) {
                if (!deleteEntry(entries.dir, id)) {
                    allDeleted = false;
                } else {
                    // 文件删除成功才清理该条目的自动音频缓存（找不到或删失败都静默，
                    // 绝不扩大清扫范围；PrebuiltAudioStore 内部自行判定挂载）
                    PrebuiltAudioStore.purgeMachineEntryAudio(entries.dir, id);
                }
            }
        }
        if (allWritten && allDeleted) {
            entries.dirtyIds.clear();
            entries.removedIds.clear();
        }
    }

    /** 读某指纹某来源的条目列表（不可变快照，调用方只读）；目录不可枚举时返回空列表 */
    public List<PrebuiltEntry> entries(String fingerprint, String source) {
        SourceEntries entries = ensureLoaded(fingerprint, source);
        if (entries == null) {
            return List.of();
        }
        return List.copyOf(entries.entries.values());
    }

    /**
     * 向匹配指纹 + 来源的子目录追加一条，并把条目数修剪到容量内（每指纹每来源独立计数）。
     * <p>
     * 追加前先在本来源目录内确认 {@code entryId} 可用：首个候选是传入条目本身的 UUID，
     * 冲突时最多重新随机两次（共三次尝试），三次尝试耗尽则无变更返回 null——不淘汰、不追加、
     * 不改任何标记，只记一条警告（见 {@link #resolveAvailableEntry}）。
     * <p>
     * 超出容量按入库时间最早优先淘汰；时间相同按 {@code entryId} 稳定决胜，淘汰顺序确定。
     * 淘汰只从内存移除并登记待删清单，实际删除文件推迟到下一次落盘（见 {@link #saveDirty}），
     * 不触碰旧版扁平 JSON。指纹/来源非法或目录被锁为只读时丢弃本次追加返回 null
     * （正常生成流程继续，不影响玩家可见行为）。
     *
     * @return 实际接收入库的条目（UUID 冲突重随机后的最终身份，供预制语音缓存按其归属落盘）；
     *         入库被拒时为 null
     */
    @Nullable
    public PrebuiltEntry append(String fingerprint, String source, PrebuiltEntry entry, int maxEntries) {
        SourceEntries entries = ensureLoaded(fingerprint, source);
        if (entries == null) {
            return null;
        }
        // 目录锁只读：读取都失败时放弃入库，不改任何内存标记（正常生成与回复交付不受影响）
        if (entries.unreadable) {
            return null;
        }
        // 先确定 UUID 在本来源目录内可用，再淘汰追加；三次尝试耗尽则无变更返回，
        // 不淘汰、不追加、不触碰脏标记与待删除集合（容量淘汰只发生在已有可用候选之后）
        PrebuiltEntry available = resolveAvailableEntry(entries, entry);
        if (available == null) {
            return null;
        }
        // 先删最旧腾出空位再追加；maxEntries 由调用方按配置现读
        while (entries.entries.size() >= maxEntries) {
            if (evictOldest(entries) == null) {
                break;
            }
        }
        entries.entries.put(available.entryId, available);
        entries.dirtyIds.add(available.entryId);
        return available;
    }

    /**
     * 有界 UUID 冲突选择：返回 {@code entryId} 在本来源目录内确认可用的条目；
     * 三次尝试耗尽返回 null（调用方据此放弃本次入库）。
     * 可用判定：不占内存条目表、不在待删除清单，且磁盘目标文件名确认不存在——
     * 损坏或校验失败未入缓存的文件同样占着文件名，绝不覆盖；UUID 只出现在待删除清单时
     * 也视为不可用（对应文件此轮稍后还要删除）。只检查目标这一个路径，不扫描其他目录、
     * 不读取文件内容；相同 UUID 位于其他指纹或来源目录不构成本目录冲突，不做全局去重。
     * <p>
     * 本方法只做检查与候选构造，不改库存、脏标记、待删除集合，也不创建目录或文件。
     */
    private static PrebuiltEntry resolveAvailableEntry(SourceEntries entries, PrebuiltEntry entry) {
        PrebuiltEntry candidate = entry;
        for (int attempt = 1; attempt <= APPEND_UUID_MAX_ATTEMPTS; attempt++) {
            boolean free;
            try {
                free = isEntryIdFree(entries, candidate.entryId);
            } catch (IOException | RuntimeException e) {
                // 可用性检查自身失败（安全策略、I/O 错误等）：告警并放弃本次入库，不向调用链抛出
                MaidSelfTalkMod.LOGGER.warn("Prebuilt reuse append dropped: entry id availability "
                        + "check failed in {} ({})", entries.dir, e.toString());
                return null;
            }
            if (free) {
                // 首个候选即可用时直接使用原条目，不做任何重新随机
                return candidate;
            }
            if (attempt < APPEND_UUID_MAX_ATTEMPTS) {
                candidate = withEntryId(entry, UUID.randomUUID());
            }
        }
        MaidSelfTalkMod.LOGGER.warn("Prebuilt reuse append dropped after {} UUID attempts in {} "
                + "(all occupied), entry not stored", APPEND_UUID_MAX_ATTEMPTS, entries.dir);
        return null;
    }

    /** 候选 UUID 是否在本来源目录内可用：内存条目与待删清单都不占，且磁盘目标文件确认不存在 */
    private static boolean isEntryIdFree(SourceEntries entries, UUID id) throws IOException {
        if (entries.entries.containsKey(id) || entries.removedIds.contains(id)) {
            return false;
        }
        // notExists 确认「不存在」才可用；文件存在或无法确认存在性（返回 false）一律视为占用
        return Files.notExists(entries.dir.resolve("entry-" + id + ".md"), LinkOption.NOFOLLOW_LINKS);
    }

    /** 构造 UUID 重试候选：除 entryId 外，时间、环境、聊天与 TTS 文本语言、来源全部沿用原条目 */
    private static PrebuiltEntry withEntryId(PrebuiltEntry entry, UUID newId) {
        return new PrebuiltEntry(newId, entry.createdAt, entry.env, entry.chatLanguage,
                entry.chatText, entry.ttsLanguage, entry.ttsText, entry.origin);
    }

    /** 淘汰排序：入库时间字符串（格式天然可字典序比较）最早优先，同秒按 entryId 稳定决胜 */
    private static boolean isOlder(UUID a, PrebuiltEntry ea, UUID b, PrebuiltEntry eb) {
        int byDate = ea.createdAt.compareTo(eb.createdAt);
        return byDate != 0 ? byDate < 0 : a.toString().compareTo(b.toString()) < 0;
    }

    /**
     * 淘汰最旧一条（扫描裁剪与追加腾位共用同一排序）：入库时间升序最早优先，
     * 同秒按 entryId 字符串升序稳定决胜。返回被淘汰的 id（库空返回 null）；
     * 淘汰只从内存移除并登记待删清单，实际删除文件推迟到下一次落盘。
     */
    private static UUID evictOldest(SourceEntries entries) {
        UUID oldest = null;
        PrebuiltEntry oldestEntry = null;
        for (Map.Entry<UUID, PrebuiltEntry> candidate : entries.entries.entrySet()) {
            if (oldestEntry == null || isOlder(candidate.getKey(), candidate.getValue(),
                    oldest, oldestEntry)) {
                oldest = candidate.getKey();
                oldestEntry = candidate.getValue();
            }
        }
        if (oldest == null) {
            return null;
        }
        entries.entries.remove(oldest);
        entries.removedIds.add(oldest);
        // 被淘汰条目若还带着未写盘的脏标记，一并撤销——它已不在库中，不再有对应文件
        entries.dirtyIds.remove(oldest);
        return oldest;
    }

    /**
     * 扫描重算容量：合法新格式条目加载完后按当前容量裁剪，超额 id 转入待删除清单
     * （文件在下一次落盘时删除）。旧格式、损坏文件未入内存，不会被列入删除清单；
     * 容量调小后重启即按新容量重算，此前因删除失败留下的条目会被重新评估。
     */
    private static void trimToCapacity(SourceEntries entries, int maxEntries) {
        while (entries.entries.size() > maxEntries) {
            if (evictOldest(entries) == null) {
                break;
            }
        }
    }

    /**
     * 覆写<b>仍在本库中</b>的指定条目的 TTS 段（按存储条目 {@code entryId} 直接定位）。
     * <p>
     * 人类条目（{@link Origin#CUSTOM}）只读：直接返回 false，文件保持不变（调用侧也不应对
     * 人类条目发起覆写）。机器条目已被容量修剪淘汰、不在缓存中时同样返回 false、什么都不写——
     * 绝不借覆写复活旧条目，也不重新扫描磁盘。只改这一条的 ttsText/ttsLanguage，
     * 不动 createdAt 与环境表。
     */
    public boolean updateTts(String fingerprint, String source, PrebuiltEntry target,
                             String ttsText, String ttsLanguage) {
        if (target == null || target.entryId == null || target.origin != Origin.MACHINE) {
            return false;
        }
        SourceEntries entries = ensureLoaded(fingerprint, source);
        if (entries == null) {
            return false;
        }
        // 目录锁只读：不返回任何修改，保持文件与标记原样
        if (entries.unreadable) {
            return false;
        }
        PrebuiltEntry existing = entries.entries.get(target.entryId);
        if (existing == null) {
            return false;
        }
        PrebuiltEntry updated = new PrebuiltEntry(existing.entryId, existing.createdAt, existing.env,
                existing.chatLanguage, existing.chatText, ttsLanguage, ttsText, existing.origin);
        entries.entries.put(existing.entryId, updated);
        entries.dirtyIds.add(existing.entryId);
        return true;
    }

    // ===== 内部结构 =====

    /** 单个来源子目录的内存态：解析结果 + 脏/删除标记 + 只读闩锁 */
    private static final class SourceEntries {
        final Path dir;
        /** entryId → 条目（LinkedHashMap 保持加载与追加顺序，输出稳定） */
        final LinkedHashMap<UUID, PrebuiltEntry> entries = new LinkedHashMap<>();
        /** 待写盘的条目（新增或 TTS 更新） */
        final Set<UUID> dirtyIds = new LinkedHashSet<>();
        /** 已淘汰、待下一次落盘时删除文件的条目 */
        final Set<UUID> removedIds = new LinkedHashSet<>();
        /** 目录无法枚举时的本次运行只读闩锁：不读不写，绝不用空库覆盖已有库存 */
        boolean unreadable;

        SourceEntries(Path dir) {
            this.dir = dir;
        }
    }

    /**
     * 条目来源标记：机器生成（指纹目录，参与容量淘汰、可回写 TTS）或人类手写
     * （{@code custom/}，只读、不淘汰、不回写）。只用于匹配语义分流，不写入文件。
     */
    public enum Origin {
        MACHINE, CUSTOM
    }

    /**
     * 库内单条预制回复：条目来源、存储条目 UUID（机器条目与文件名一致；人类条目由相对路径
     * 派生、只在内存里用）、入库时间、逐 key 环境取值（与最终注入内容同源）、上屏文本及其语言、
     * TTS 文本及其语言（TTS 未生成时两者为 null，读取侧会回退为 chatText）。
     * 来源与指纹都不进条目文件——由所在目录表达。
     */
    public static final class PrebuiltEntry {
        public final UUID entryId;
        public final String createdAt;
        public final Map<String, String> env;
        public final String chatLanguage;
        public final String chatText;
        public final String ttsLanguage;
        public final String ttsText;
        public final Origin origin;

        public PrebuiltEntry(UUID entryId, String createdAt, Map<String, String> env, String chatLanguage,
                             String chatText, String ttsLanguage, String ttsText, Origin origin) {
            this.entryId = entryId;
            this.createdAt = createdAt;
            this.env = env;
            this.chatLanguage = chatLanguage;
            this.chatText = chatText;
            this.ttsLanguage = ttsLanguage;
            this.ttsText = ttsText;
            this.origin = origin;
        }
    }

    /**
     * 惰性加载：首次访问某指纹某来源才扫描对应子目录；结果（含只读判定）缓存到实例释放。
     * 指纹/来源非法返回 null（调用方按空库/无操作处理）。
     */
    private SourceEntries ensureLoaded(String fingerprint, String source) {
        if (fingerprint == null || !FINGERPRINT_PATTERN.matcher(fingerprint).matches()) {
            return null;
        }
        String sourceDir = sourceDirName(source);
        if (sourceDir == null) {
            return null;
        }
        return fingerprints
                .computeIfAbsent(fingerprint, fp -> new LinkedHashMap<>())
                .computeIfAbsent(sourceDir, src -> scan(fingerprint, src));
    }

    /** 来源常量 → 子目录名；只接受两个固定常量，杜绝任意字符串拼进路径 */
    private static String sourceDirName(String source) {
        if (SOURCE_WELCOME.equals(source) || SOURCE_SELF_TALK.equals(source)) {
            return source;
        }
        return null;
    }

    /** 扫描单个来源子目录：损坏/不识别/文件名不符的文件跳过并告警，其余解析入缓存 */
    private SourceEntries scan(String fingerprint, String source) {
        SourceEntries result = new SourceEntries(dir.resolve(GENERATED_DIR)
                .resolve(source).resolve(FINGERPRINT_DIR_PREFIX + fingerprint));
        if (!Files.isDirectory(result.dir)) {
            // 没有目录就是空库：不创建目录（首次成功写入时才建），也不算坏档
            return result;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(result.dir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            result.unreadable = true;
            MaidSelfTalkMod.LOGGER.warn("Prebuilt reuse source dir {} cannot be listed ({}), "
                    + "locked read-only for this run", result.dir, e.toString());
            return result;
        }
        for (Path file : files) {
            parseEntryFile(file, result);
        }
        // 扫描重算容量：合法新格式条目加载完后按当前容量裁剪（容量来源失败只跳过裁剪，不影响读取）
        int capacity;
        try {
            capacity = scanCapacity.getAsInt();
        } catch (RuntimeException e) {
            MaidSelfTalkMod.LOGGER.warn("Failed to read prebuilt reuse capacity for {} ({}), skip trimming",
                    result.dir, e.toString());
            return result;
        }
        trimToCapacity(result, capacity);
        return result;
    }

    /** 解析单个条目文件入缓存；任何不符只跳过该文件，不改动原文件 */
    private static void parseEntryFile(Path file, SourceEntries result) {
        PrebuiltEntryCodec.ReadResult read;
        try {
            read = PrebuiltEntryCodec.readMachine(Files.readString(file, StandardCharsets.UTF_8),
                    file.getFileName().toString(),
                    // 惰性提供文件修改时间：编解码器仅在 createdAt 缺失或空白时才读取文件属性，
                    // 读取失败经 UncheckedIOException 由补日期逻辑形成失败告警，不会赋予虚构日期
                    () -> {
                        try {
                            return Files.getLastModifiedTime(file).toInstant();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException | RuntimeException e) {
            MaidSelfTalkMod.LOGGER.warn("Prebuilt reuse entry file {} unreadable ({}), skipped",
                    file, e.toString());
            return;
        }
        for (PrebuiltEntryCodec.Issue issue : read.issues()) {
            MaidSelfTalkMod.LOGGER.warn("Prebuilt reuse entry file {}: {}", file, issue.message());
        }
        if (read.entry() != null) {
            result.entries.put(read.entry().entryId, read.entry());
        }
    }

    /**
     * 单条目文件写入：临时文件 + 原子替换。写入边界同时捕获 I/O 与预期运行时故障
     * （如序列化异常），单条失败不影响其余条目继续写；失败返回 false，
     * 保留原文件与脏标记下轮重试，绝不把写一半的临时内容当成已保存。
     */
    private static boolean writeEntryFile(Path dir, PrebuiltEntry entry) {
        Path file = dir.resolve("entry-" + entry.entryId + ".md");
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(file.getFileName() + ".tmp");
            Files.writeString(tmp, PrebuiltEntryCodec.writeMachine(entry), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            MaidSelfTalkMod.LOGGER.warn("Failed to save prebuilt reuse entry file {}: {}",
                    file, e.toString());
            return false;
        }
    }

    /** 生产删除操作：文件不存在视为已删除；I/O 与预期运行时故障返回 false，下轮存档重试 */
    private static boolean deleteEntryFile(Path dir, UUID entryId) {
        Path file = dir.resolve("entry-" + entryId + ".md");
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException | RuntimeException e) {
            MaidSelfTalkMod.LOGGER.warn("Failed to delete prebuilt reuse entry file {}: {}",
                    file, e.toString());
            return false;
        }
    }

    /** 淘汰条目文件删除（经注入的删除操作）：预期运行时故障转为本轮失败，不影响其余条目 */
    private boolean deleteEntry(Path dir, UUID entryId) {
        try {
            return deleteOperation.test(dir, entryId);
        } catch (RuntimeException e) {
            MaidSelfTalkMod.LOGGER.warn("Failed to delete prebuilt reuse entry file entry-{}.md in {}: {}",
                    entryId, dir, e.toString());
            return false;
        }
    }
}
