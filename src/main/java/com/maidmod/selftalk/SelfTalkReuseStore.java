package com.maidmod.selftalk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 预制复用条目的世界级 JSON 库：每个身份指纹一个文件，按来源（欢迎语／自话）分两个数组。
 * <p>
 * 存储位置 {@code <世界根>/data/maid_self_talk/prebuilt/<fingerprint>.json}——
 * 不随女仆 NBT、不随玩家设置走，同服同世界共享；换世界即换库，不做跨世界同步，
 * 也不下发客户端。
 * <p>
 * 磁盘生命周期（刻意最小化）：
 * <ul>
 *   <li>内存按服务端实例隔离（{@link #of}），首次访问某指纹才读盘（惰性加载）；</li>
 *   <li>写入只置脏，主线程不落盘——脏文件统一在 overworld 存档事件
 *       （{@code LevelEvent.Save}）与服务器停止（{@code ServerStoppedEvent} → {@link #release}）时写出；</li>
 *   <li>写出走同目录临时文件 + 原子替换；失败保留原文件与脏标记，下轮存档重试；</li>
 *   <li>解析失败或版本不识别的文件：本次运行不读也不写（坏档闩锁），记一次诊断日志，
 *       正常生成流程不受影响，<b>绝不</b>用空库覆盖原文件。</li>
 * </ul>
 * 全部方法仅服务端主线程调用（写入在真实回复入库时、读取在触发匹配时，
 * 落盘在存档事件里），无需加锁；静态表加同步仅为防跨线程查询。
 */
public final class SelfTalkReuseStore {

    /** JSON 中的欢迎语数组键（来源由所在数组表达，条目内不重复记来源） */
    public static final String SOURCE_WELCOME = "welcome";
    /** JSON 中的自话数组键 */
    public static final String SOURCE_SELF_TALK = "self-talk";

    private static final int SCHEMA_VERSION = 1;
    /** 玩家级设置里单条目的容量上限由配置给出，这里只挡非法调用 */
    private static final Map<MinecraftServer, SelfTalkReuseStore> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final MinecraftServer server;
    private final Path dir;
    private final Map<String, FingerprintFile> files = new LinkedHashMap<>();

    private SelfTalkReuseStore(MinecraftServer server) {
        this.server = server;
        this.dir = server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("maid_self_talk").resolve("prebuilt");
    }

    /** 取（或创建）某服务端实例的内存库；实例由 {@link #release} 在服务器停止时移除 */
    public static SelfTalkReuseStore of(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, SelfTalkReuseStore::new);
    }

    /** 服务器停止：先写出脏文件，再释放该实例的全部内存缓存 */
    public static void release(MinecraftServer server) {
        SelfTalkReuseStore store = INSTANCES.remove(server);
        if (store != null) {
            store.saveDirty();
        }
    }

    /** 把全部脏文件写盘（overworld 存档事件与服务器停止时调用） */
    public void saveDirty() {
        for (FingerprintFile file : files.values()) {
            if (file.dirty && !file.broken) {
                write(file);
            }
        }
    }

    /** 读某指纹某来源的条目列表（不可变副本，调用方只读） */
    public List<PrebuiltEntry> entries(String fingerprint, String source) {
        FingerprintFile file = ensureLoaded(fingerprint);
        if (file.broken) {
            return List.of();
        }
        return parseEntries(file.root, source);
    }

    /**
     * 向匹配指纹 + 来源的数组追加一条，并把数组修剪到容量内。
     * <p>
     * 只增改不改写既有条目：先按存档日期字符串删除最旧条目直到 {@code size < maxEntries}，
     * 再追加新条目——同秒条目的新旧由数组内的持久顺序表达，不依赖日期字符串细分。
     * 坏档指纹直接丢弃本次追加（正常生成流程继续，不影响玩家可见行为）。
     */
    public void append(String fingerprint, String source, PrebuiltEntry entry, int maxEntries) {
        FingerprintFile file = ensureLoaded(fingerprint);
        if (file.broken) {
            return;
        }
        JsonArray array = file.root.has(source) && file.root.get(source).isJsonArray()
                ? file.root.getAsJsonArray(source) : new JsonArray();
        // 先删最旧（按存档日期字符串取最小者，缺日期者排最后），腾出空位再追加；
        // maxEntries 由调用方按配置现读
        while (array.size() >= maxEntries) {
            int oldest = 0;
            for (int i = 1; i < array.size(); i++) {
                String date = createdAtOf(array.get(i));
                String current = createdAtOf(array.get(oldest));
                if (date != null && (current == null || date.compareTo(current) < 0)) {
                    oldest = i;
                }
            }
            array.remove(oldest);
        }
        array.add(toJson(entry));
        file.root.add(source, array);
        file.dirty = true;
    }

    /**
     * 覆写<b>仍在本库中</b>的指定条目的 TTS 段（按入库时间 + 上屏语言 + 上屏文本定位）。
     * <p>
     * 条目已被容量修剪淘汰时返回 false、什么都不写——绝不借覆写复活旧条目。
     * 只改这一条的 ttsText/ttsLanguage，不动 createdAt 与环境表。
     */
    public boolean updateTts(String fingerprint, String source, PrebuiltEntry target,
                             String ttsText, String ttsLanguage) {
        FingerprintFile file = ensureLoaded(fingerprint);
        if (file.broken || !file.root.has(source) || !file.root.get(source).isJsonArray()) {
            return false;
        }
        for (JsonElement element : file.root.getAsJsonArray(source)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            if (matchesTarget(object, target)) {
                object.add("ttsText", ttsText == null ? JsonNull.INSTANCE : new JsonPrimitive(ttsText));
                object.add("ttsLanguage", ttsLanguage == null
                        ? JsonNull.INSTANCE : new JsonPrimitive(ttsLanguage));
                file.dirty = true;
                return true;
            }
        }
        return false;
    }

    /** 条目定位：入库时间 + 上屏语言 + 上屏文本三元组（手改文件缺字段视为不匹配） */
    private static boolean matchesTarget(JsonObject object, PrebuiltEntry target) {
        try {
            return target.createdAt.equals(object.get("createdAt").getAsString())
                    && target.chatLanguage.equals(object.get("chatLanguage").getAsString())
                    && target.chatText.equals(object.get("chatText").getAsString());
        } catch (Exception e) {
            return false;
        }
    }

    // ===== 内部结构 =====

    /** 单个指纹文件的内存态：解析结果 + 脏标记 + 坏档闩锁 */
    private static final class FingerprintFile {
        final Path path;
        JsonObject root = new JsonObject();
        boolean dirty;
        boolean broken;

        FingerprintFile(Path path) {
            this.path = path;
        }
    }

    /**
     * 库内单条预制回复：入库时间、逐 key 环境取值（与最终注入内容同源）、
     * 上屏文本及其语言、TTS 文本及其语言（TTS 未生成时两者为 null）。
     * 来源不进条目——由所在数组表达。
     */
    public static final class PrebuiltEntry {
        public final String createdAt;
        public final Map<String, String> env;
        public final String chatLanguage;
        public final String chatText;
        public final String ttsLanguage;
        public final String ttsText;

        public PrebuiltEntry(String createdAt, Map<String, String> env, String chatLanguage,
                             String chatText, String ttsLanguage, String ttsText) {
            this.createdAt = createdAt;
            this.env = env;
            this.chatLanguage = chatLanguage;
            this.chatText = chatText;
            this.ttsLanguage = ttsLanguage;
            this.ttsText = ttsText;
        }
    }

    /** 惰性加载：首次访问某指纹才读盘；结果（含坏档判定）缓存到实例释放 */
    private FingerprintFile ensureLoaded(String fingerprint) {
        return files.computeIfAbsent(fingerprint, fp -> {
            FingerprintFile file = new FingerprintFile(dir.resolve(fp + ".json"));
            if (!Files.exists(file.path)) {
                return file;
            }
            try {
                String raw = Files.readString(file.path, StandardCharsets.UTF_8);
                JsonObject parsed = JsonParser.parseString(raw).getAsJsonObject();
                if (parsed.get("schemaVersion") == null
                        || parsed.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                    file.broken = true;
                    MaidSelfTalkMod.LOGGER.warn("Prebuilt reuse file {} has unsupported schema, ignored this run",
                            file.path);
                    return file;
                }
                file.root = parsed;
            } catch (Exception e) {
                file.broken = true;
                MaidSelfTalkMod.LOGGER.warn("Prebuilt reuse file {} unreadable ({}), ignored this run",
                        file.path, e.toString());
            }
            return file;
        });
    }

    /** 从缓存根解析某来源的条目；单条缺字段/类型错即跳过该条，不影响其余 */
    private static List<PrebuiltEntry> parseEntries(JsonObject root, String source) {
        if (!root.has(source) || !root.get(source).isJsonArray()) {
            return List.of();
        }
        List<PrebuiltEntry> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray(source)) {
            if (!element.isJsonObject()) {
                continue;
            }
            PrebuiltEntry entry = fromJson(element.getAsJsonObject());
            if (entry != null) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static PrebuiltEntry fromJson(JsonObject object) {
        try {
            String createdAt = object.get("createdAt").getAsString();
            String chatLanguage = object.get("chatLanguage").getAsString();
            String chatText = object.get("chatText").getAsString();
            if (StringUtils.isBlank(createdAt) || StringUtils.isBlank(chatLanguage) || StringUtils.isBlank(chatText)) {
                return null;
            }
            Map<String, String> env = new LinkedHashMap<>();
            if (object.has("env") && object.get("env").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : object.getAsJsonObject("env").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        env.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
            String ttsLanguage = object.has("ttsLanguage") && !object.get("ttsLanguage").isJsonNull()
                    ? object.get("ttsLanguage").getAsString() : null;
            String ttsText = object.has("ttsText") && !object.get("ttsText").isJsonNull()
                    ? object.get("ttsText").getAsString() : null;
            return new PrebuiltEntry(createdAt, env, chatLanguage, chatText, ttsLanguage, ttsText);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject toJson(PrebuiltEntry entry) {
        JsonObject object = new JsonObject();
        object.addProperty("createdAt", entry.createdAt);
        JsonObject env = new JsonObject();
        entry.env.forEach(env::addProperty);
        object.add("env", env);
        object.addProperty("chatLanguage", entry.chatLanguage);
        object.addProperty("chatText", entry.chatText);
        object.add("ttsLanguage", entry.ttsLanguage == null
                ? JsonNull.INSTANCE : new JsonPrimitive(entry.ttsLanguage));
        object.add("ttsText", entry.ttsText == null
                ? JsonNull.INSTANCE : new JsonPrimitive(entry.ttsText));
        return object;
    }

    /** 读取条目的入库日期；手改文件缺字段时按 null 处理（排在删除序列最后） */
    private static String createdAtOf(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonElement date = element.getAsJsonObject().get("createdAt");
        return date != null && date.isJsonPrimitive() ? date.getAsString() : null;
    }

    /** 临时文件 + 原子替换写盘；失败保留原文件与脏标记，下轮存档重试 */
    private void write(FingerprintFile file) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(file.path.getFileName() + ".tmp");
            Files.writeString(tmp, file.root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file.path, StandardCopyOption.REPLACE_EXISTING);
            }
            file.dirty = false;
        } catch (IOException e) {
            MaidSelfTalkMod.LOGGER.warn("Failed to save prebuilt reuse file {}: {}",
                    file.path, e.toString());
        }
    }
}
