package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.gptsovits.TTSGptSovitsSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.minimax.TTSMiniMaxSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.player2.TTSPlayer2Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.system.TTSSystemSite;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 预制压缩语音缓存（实验性，随「保存并复用预制语音」世界开关生效）：复用命中时的音频查找、
 * 真实合成结果的待保存登记与增量落盘。只保存识别为 MP3、Ogg Opus 或 Ogg Vorbis 的原始字节；
 * WAV、裸 Opus 与未知容器一律不写缓存，绝不通过改扩展名冒充压缩格式。
 * <p>
 * 目录结构（挂在既有预制库存根目录下，人工音频只读、永不落本类之手）：
 * <pre>
 * &lt;世界根&gt;/data/maid_self_talk/prebuilt/
 *   generated/&lt;source&gt;/v1-&lt;指纹&gt;/entry-&lt;UUID&gt;.md              # 既有机器条目（本类只读其定位）
 *   custom/&lt;scope&gt;/&lt;source&gt;/greeting.md                       # 既有 custom 条目
 *   custom/&lt;scope&gt;/&lt;source&gt;/greeting.mp3|.ogg                  # 人工音频（同目录同主名，无语言后缀，只读）
 *   generated-audio/generated/&lt;source&gt;/v1-&lt;指纹&gt;/&lt;voice-key&gt;/  # 机器条目自动音频
 *     entry-&lt;UUID&gt;.&lt;zh-CN&gt;.mp3|.ogg + 同名 .json
 *   generated-audio/custom/&lt;scope&gt;/&lt;source&gt;/&lt;voice-key&gt;/       # custom 条目自动音频
 *     greeting.&lt;zh-CN&gt;.mp3|.ogg + 同名 .json
 * </pre>
 * <p>
 * 声音标识键（&lt;voice-key&gt;）：供应商 api_type + 站点 ID + 端点身份（url）+ 有效音色 ID 的
 * SHA-256；MiniMax 额外混入 siteModel，GPT-SoVITS 额外混入参考音频路径、辅助路径、
 * 提示文本／语言与切分方式。密钥与 headers 绝不参与摘要、不落盘。
 * 语言用完整目标 TTS 语言标签后缀（zh_cn → zh-CN，不只保留 zh）。
 * <p>
 * 生命周期与 {@link SelfTalkReuseStore} 同节奏：待保存先入内存队列（同路径幂等覆盖），
 * overworld 存档与服务器停止时以「临时文件 + 原子替换」写出；查找时先看未落盘的待保存字节。
 * 机器条目被容量淘汰且其 Markdown 文件<b>删除成功</b>后，才清理该条目在各声音、各语言下的
 * 自动音频与配套 JSON（不扩大为全目录清扫，不触碰人工文件）；关闭开关不清空已有库存。
 * 全部方法仅服务端主线程调用（字节捕获经 {@link PrebuiltAudioContext} 在主线程结算），
 * 实例表加同步仅为防跨线程查询。
 */
public final class PrebuiltAudioStore {

    /** 自动音频根目录（与既有 {@code generated/}、{@code custom/} 平级） */
    static final String AUDIO_DIR_NAME = "generated-audio";
    private static final String MP3_EXT = ".mp3";
    private static final String OGG_EXT = ".ogg";
    private static final String JSON_EXT = ".json";
    /** 身份指纹目录前缀（与 {@code SelfTalkReuseStore} 的 v1 规则一致） */
    private static final String FINGERPRINT_DIR_PREFIX = "v1-";

    /** 实例表按服务端弱引用持有，服务器停止时由 {@link #release} 写出并移除 */
    private static final Map<MinecraftServer, PrebuiltAudioStore> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Path prebuiltDir;
    /** 待写出队列（主线程专用；同路径覆盖，查找可读未落盘字节） */
    private final List<PendingAudio> pendingWrites = new ArrayList<>();

    /** 一条待保存音频：相对 prebuilt 根的目录（/ 分隔）、音频与 JSON 文件名、字节与 JSON 内容 */
    private record PendingAudio(String relDir, String audioName, byte[] audio,
                                String jsonName, String ttsText, String ttsLanguage) {
    }

    private PrebuiltAudioStore(Path prebuiltDir) {
        this.prebuiltDir = prebuiltDir;
    }

    /** 取（或创建）某服务端实例的音频缓存；overworld 存档事件经此写盘，实例由 {@link #release} 移除 */
    public static PrebuiltAudioStore of(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new PrebuiltAudioStore(
                s.getWorldPath(LevelResource.ROOT)
                        .resolve("data").resolve("maid_self_talk").resolve("prebuilt")));
    }

    /** 服务器停止：写出全部待保存音频并释放实例 */
    public static void release(MinecraftServer server) {
        PrebuiltAudioStore store = INSTANCES.remove(server);
        if (store != null) {
            store.saveDirty();
        }
    }

    // ===== 识别与命名 =====

    /** 支持保存/播放的压缩音频格式（OGG 含 Opus 与 Vorbis 两种编码，扩展名同为 .ogg） */
    enum AudioFormat { MP3, OGG }

    /**
     * 压缩格式识别（标准库，只看字节）：MP3 认 ID3v2 头或 MPEG 帧同步（版本/层/位率/采样率
     * 合法位组合），OGG 认 {@code OggS} 容器且首页内能找到 OpusHead 或 \x01vorbis 编码头。
     * WAV（RIFF）、裸 Opus 与其余未知格式返回 null——调用方不写缓存、不投放播放。
     */
    @Nullable
    static AudioFormat detect(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        // MP3：ID3v2 "ID3"
        if ((data[0] & 0xFF) == 0x49 && (data[1] & 0xFF) == 0x44 && (data[2] & 0xFF) == 0x33) {
            return AudioFormat.MP3;
        }
        // MP3：MPEG 帧同步 11 位 + 版本非保留 + 层非空 + 位率/采样率索引合法
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0) {
            int version = (data[1] >> 3) & 0x03;
            int layer = (data[1] >> 1) & 0x03;
            int bitrate = (data[2] >> 4) & 0x0F;
            int sampleRate = (data[2] >> 2) & 0x03;
            if (version != 1 && layer != 0 && bitrate != 0 && bitrate != 15 && sampleRate != 3) {
                return AudioFormat.MP3;
            }
            return null;
        }
        // OGG 容器：首页前 100 字节内识别 Opus / Vorbis 编码头
        if ((data[0] & 0xFF) == 0x4F && (data[1] & 0xFF) == 0x67
                && (data[2] & 0xFF) == 0x67 && (data[3] & 0xFF) == 0x53) {
            int limit = Math.min(data.length - 8, 100);
            for (int i = 0; i < limit; i++) {
                if (data[i] == 0x01 && data[i + 1] == 'v' && data[i + 2] == 'o' && data[i + 3] == 'r'
                        && data[i + 4] == 'b' && data[i + 5] == 'i' && data[i + 6] == 's') {
                    return AudioFormat.OGG;
                }
                if (data[i] == 'O' && data[i + 1] == 'p' && data[i + 2] == 'u' && data[i + 3] == 's'
                        && data[i + 4] == 'H' && data[i + 5] == 'e' && data[i + 6] == 'a' && data[i + 7] == 'd') {
                    return AudioFormat.OGG;
                }
            }
        }
        return null;
    }

    /**
     * 声音标识键：供应商 api_type、站点 ID、端点身份（url）、有效音色 ID 与供应商特有字段的
     * SHA-256（64 位小写十六进制）。长度前缀定界防拼接歧义；密钥与 headers 不参与。
     * 摘要失败（JVM 必带 SHA-256，理论不可达）返回 null，调用方放弃本音频。
     */
    @Nullable
    static String voiceKeyOf(TTSSite site, String modelId) {
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, site.getApiType());
        appendField(canonical, site.id());
        appendField(canonical, site.url());
        appendField(canonical, modelId);
        if (site instanceof TTSMiniMaxSite minimax) {
            // MiniMax 的 siteModel 是独立于 voice_id 的合成模型参数，换模型必须换缓存
            appendField(canonical, minimax.siteModel());
        }
        if (site instanceof TTSGptSovitsSite gptSovits) {
            // GPT-SoVITS 的「声音」由参考音频与提示决定，这些站点字段全部参与
            appendField(canonical, gptSovits.refAudioPath());
            appendField(canonical, StringUtils.join(gptSovits.auxRefAudioPaths(), '\n'));
            appendField(canonical, gptSovits.promptText());
            appendField(canonical, gptSovits.promptLang());
            appendField(canonical, gptSovits.textSplitMethod());
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 供应商是否被排除：系统 TTS 与 Player2 没有服务端音频回传，不查音频、不建缓存（mixin 也读） */
    public static boolean isExcludedProvider(TTSSite site) {
        return TTSSystemSite.API_TYPE.equals(site.getApiType())
                || TTSPlayer2Site.API_TYPE.equals(site.getApiType());
    }

    /** 完整目标语言标签后缀：zh_cn → zh-CN（首段小写、次段起大写，段数不足只给主段） */
    static String languageTagOf(String ttsLanguage) {
        if (ttsLanguage == null || ttsLanguage.isBlank()) {
            return "unknown";
        }
        String[] parts = ttsLanguage.split("_");
        StringBuilder tag = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            tag.append('-').append(parts[i].toUpperCase(Locale.ROOT));
        }
        return tag.toString();
    }

    /** 规范序列化（与 {@link SelfTalkReuseService} 同构）：长度前缀定界，null 写作空段 */
    private static void appendField(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append("0:");
        } else {
            canonical.append(value.length()).append(':').append(value);
        }
        canonical.append('\n');
    }

    /** 身份指纹是否为本 mod 产出的 64 位小写十六进制（杜绝任意字符串拼进路径） */
    private static boolean isStorableFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() != 64) {
            return false;
        }
        for (int i = 0; i < fingerprint.length(); i++) {
            char c = fingerprint.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    // ===== 复用命中查找 =====

    /**
     * 复用命中条目的音频查找（服务端主线程，在 {@code deliverReused} 的翻译分流之前）：
     * 供应商被排除或声音键失败时返回 null（该次投放照常实时合成，不建音频上下文）；
     * 否则按「人工通用音频 → 当前声音与完整目标语言的自动音频」固定顺序查找——
     * 人工音频只认源 Markdown 同目录、同主名、无语言后缀的 .mp3/.ogg（同时存在优先 MP3），
     * 自动音频必须有配套 JSON（ttsText 非空白且 ttsLanguage 与当前目标语言严格相等），
     * 其他语言文件不能顶替当前语言。查得即返回携带命中字节的上下文（直接缓存播放）；
     * 未查得返回捕获上下文（真实合成结果稍后按最终条目身份入库）。自动音频缺 JSON、
     * 不可读或格式不符均按未命中处理。
     */
    @Nullable
    static PrebuiltAudioContext lookupForReuse(MinecraftServer server, SelfTalkRequestToken token,
                                               String fingerprint, String source,
                                               SelfTalkReuseStore.PrebuiltEntry entry,
                                               MaidAIChatManager chatManager) {
        TTSSite site = chatManager.getTTSSite();
        if (site == null || isExcludedProvider(site)) {
            return null;
        }
        String voiceKey = voiceKeyOf(site, chatManager.getTTSModel());
        if (voiceKey == null) {
            return null;
        }
        Path prebuiltDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("maid_self_talk").resolve("prebuilt");
        String targetLanguage = chatManager.getTTSLanguage();
        String langTag = languageTagOf(targetLanguage);
        String customRelPath = entry.origin == SelfTalkReuseStore.Origin.CUSTOM
                ? PrebuiltCustomEntries.customSourceRelPath(server, entry.entryId) : null;
        byte[] audio = null;
        String deliveryTtsText = null;
        // 1) 人工通用音频（仅 custom 条目）：不绑定声音与语言，命中即跨声音、跨语言通用
        if (customRelPath != null) {
            Path sourceMd = resolveInside(prebuiltDir.resolve(SelfTalkReuseStore.CUSTOM_DIR_NAME), customRelPath);
            if (sourceMd != null) {
                audio = readManualAudio(sourceMd);
            }
        }
        // 2) 自动音频：当前声音 + 完整目标语言，JSON 必须齐备
        if (audio == null) {
            AutoTarget target = autoTargetOf(prebuiltDir, fingerprint, source, entry, voiceKey, customRelPath);
            if (target != null) {
                AutoHit hit = readAutoAudio(of(server), target, langTag, targetLanguage);
                if (hit != null) {
                    audio = hit.bytes();
                    deliveryTtsText = hit.ttsText();
                }
            }
        }
        if (audio != null) {
            return PrebuiltAudioContext.forHit(token, fingerprint, source, entry, audio, deliveryTtsText);
        }
        return PrebuiltAudioContext.forCapture(token, fingerprint, source, entry, customRelPath);
    }

    /** 自动音频定位：目录 + 文件主名（机器条目按指纹目录、custom 条目按源文件相对路径推导） */
    private record AutoTarget(String relDir, Path audioDir, String baseName) {
    }

    @Nullable
    private static AutoTarget autoTargetOf(Path prebuiltDir, String fingerprint, String source,
                                           SelfTalkReuseStore.PrebuiltEntry entry, String voiceKey,
                                           @Nullable String customRelPath) {
        if (entry.origin == SelfTalkReuseStore.Origin.MACHINE) {
            if (!isStorableFingerprint(fingerprint)) {
                return null;
            }
            String relDir = AUDIO_DIR_NAME + '/' + SelfTalkReuseStore.GENERATED_DIR + '/' + source
                    + '/' + FINGERPRINT_DIR_PREFIX + fingerprint + '/' + voiceKey;
            return new AutoTarget(relDir, prebuiltDir.resolve(relDirPartsOf(relDir)),
                    "entry-" + entry.entryId);
        }
        if (customRelPath == null) {
            return null;
        }
        // relPath 形如 "<scope>/<source>/<file>.md"：镜像 custom/ 的两层目录与人类起的主名
        String[] parts = customRelPath.split("/");
        if (parts.length != 3 || !parts[2].toLowerCase(Locale.ROOT).endsWith(".md")) {
            return null;
        }
        String relDir = AUDIO_DIR_NAME + '/' + SelfTalkReuseStore.CUSTOM_DIR_NAME + '/'
                + parts[0] + '/' + parts[1] + '/' + voiceKey;
        String stem = parts[2].substring(0, parts[2].length() - 3);
        return new AutoTarget(relDir, prebuiltDir.resolve(relDirPartsOf(relDir)), stem);
    }

    /** relDir（/ 分隔）→ 平台路径；段内已由上游结构保证（scope/来源/指纹/voice-key），不接收自由输入 */
    private static Path relDirPartsOf(String relDir) {
        Path path = Path.of(relDir.split("/")[0]);
        for (String part : relDir.split("/")) {
            path = path.resolve(part);
        }
        return path;
    }

    // ===== 待保存登记与落盘 =====

    /**
     * 登记一条待保存音频（服务端主线程，来自 {@link PrebuiltAudioContext#trySettle}）；
     * 同路径幂等覆盖。任何路径推导失败都静默放弃，绝不抛出阻断正常交付链。
     */
    static void registerPending(MinecraftServer server, String fingerprint, String source,
                                SelfTalkReuseStore.PrebuiltEntry entry, @Nullable String customRelPath,
                                String voiceKey, String ttsLanguage, String ttsText,
                                byte[] bytes, AudioFormat format) {
        try {
            PrebuiltAudioStore store = of(server);
            String langTag = languageTagOf(ttsLanguage);
            String ext = format == AudioFormat.MP3 ? MP3_EXT : OGG_EXT;
            String relDir;
            String baseName;
            if (entry.origin == SelfTalkReuseStore.Origin.MACHINE) {
                if (!isStorableFingerprint(fingerprint)) {
                    return;
                }
                relDir = AUDIO_DIR_NAME + '/' + SelfTalkReuseStore.GENERATED_DIR + '/' + source
                        + '/' + FINGERPRINT_DIR_PREFIX + fingerprint + '/' + voiceKey;
                baseName = "entry-" + entry.entryId;
            } else {
                if (customRelPath == null) {
                    return;
                }
                String[] parts = customRelPath.split("/");
                if (parts.length != 3 || !parts[2].toLowerCase(Locale.ROOT).endsWith(".md")) {
                    return;
                }
                relDir = AUDIO_DIR_NAME + '/' + SelfTalkReuseStore.CUSTOM_DIR_NAME + '/'
                        + parts[0] + '/' + parts[1] + '/' + voiceKey;
                baseName = parts[2].substring(0, parts[2].length() - 3);
            }
            String audioName = baseName + '.' + langTag + ext;
            String jsonName = baseName + '.' + langTag + JSON_EXT;
            PendingAudio pending = new PendingAudio(relDir, audioName, bytes, jsonName, ttsText, ttsLanguage);
            store.pendingWrites.removeIf(p -> p.relDir.equals(relDir) && p.audioName.equals(audioName));
            store.pendingWrites.add(pending);
        } catch (RuntimeException e) {
            MaidSelfTalkMod.LOGGER.warn("Prebuilt audio cache registration dropped: {}", e.toString());
        }
    }

    /**
     * 写出全部待保存音频（overworld 存档与服务器停止时调用）：音频与 JSON 各自走
     * 「临时文件 + 原子替换」，二者都成功才移出队列；失败保留待下轮重试，
     * 音频故障绝不阻断文本库或正常合成。
     */
    public void saveDirty() {
        Iterator<PendingAudio> iterator = pendingWrites.iterator();
        while (iterator.hasNext()) {
            PendingAudio pending = iterator.next();
            if (writeAudio(pending)) {
                iterator.remove();
            }
        }
    }

    private boolean writeAudio(PendingAudio pending) {
        Path dir = prebuiltDir.resolve(relDirPartsOf(pending.relDir()));
        try {
            Files.createDirectories(dir);
            JsonObject json = new JsonObject();
            json.addProperty("ttsText", pending.ttsText());
            json.addProperty("ttsLanguage", pending.ttsLanguage());
            byte[] jsonBody = json.toString().getBytes(StandardCharsets.UTF_8);
            return atomicWrite(dir.resolve(pending.audioName()), pending.audio())
                    && atomicWrite(dir.resolve(pending.jsonName()), jsonBody);
        } catch (IOException | RuntimeException e) {
            MaidSelfTalkMod.LOGGER.warn("Failed to save prebuilt audio {}/{}: {}",
                    pending.relDir(), pending.audioName(), e.toString());
            return false;
        }
    }

    /** 临时文件 + 原子替换写出；原子移动不受支持时退回普通替换 */
    private static boolean atomicWrite(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    // ===== 淘汰清理 =====

    /**
     * 机器条目 Markdown 文件<b>删除成功</b>后清理其全部声音、全部语言的自动音频与配套 JSON
     * （{@code SelfTalkReuseStore} 淘汰路径回调）：只按条目文件名前缀精确匹配，
     * 不扩大为目录清扫、不触碰人工文件；同时丢弃该条目尚未落盘的待保存字节，
     * 防止「先写音频后删条目」把被淘汰条目的音频复活。
     */
    static void purgeMachineEntryAudio(Path machineSourceDir, UUID entryId) {
        try {
            // machineSourceDir = <prebuilt>/generated/<source>/v1-<指纹>
            Path generatedRoot = machineSourceDir.getParent();
            if (generatedRoot == null || generatedRoot.getParent() == null
                    || generatedRoot.getParent().getParent() == null) {
                return;
            }
            Path prebuiltDir = generatedRoot.getParent().getParent();
            String source = generatedRoot.getFileName().toString();
            String fingerprintDir = machineSourceDir.getFileName().toString();
            Path audioDir = prebuiltDir.resolve(AUDIO_DIR_NAME)
                    .resolve(SelfTalkReuseStore.GENERATED_DIR).resolve(source).resolve(fingerprintDir);
            if (!Files.isDirectory(audioDir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            String prefix = "entry-" + entryId + ".";
            String pendingPrefix = AUDIO_DIR_NAME + '/' + SelfTalkReuseStore.GENERATED_DIR
                    + '/' + source + '/' + fingerprintDir + '/';
            // 待保存队列：同条目的未落盘字节一并丢弃（主线程访问，直接遍历安全）
            for (PrebuiltAudioStore store : INSTANCES.values()) {
                if (store.prebuiltDir.equals(prebuiltDir)) {
                    store.pendingWrites.removeIf(p ->
                            p.relDir().startsWith(pendingPrefix) && p.audioName().startsWith(prefix));
                }
            }
            // 各声音目录下按条目名前缀删除音频与 JSON；其他条目的文件不受影响
            try (var voiceStream = Files.list(audioDir)) {
                for (Path voiceDir : voiceStream.filter(Files::isDirectory).toList()) {
                    try (var files = Files.list(voiceDir)) {
                        for (Path file : files.filter(p -> p.getFileName().toString().startsWith(prefix)).toList()) {
                            try {
                                Files.deleteIfExists(file);
                            } catch (IOException e) {
                                MaidSelfTalkMod.LOGGER.warn("Failed to delete evicted prebuilt audio {}: {}",
                                        file, e.toString());
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            MaidSelfTalkMod.LOGGER.warn("Prebuilt audio cleanup for evicted entry {} failed: {}", entryId, e.toString());
        }
    }

    // ===== 读取 =====

    /** 自动音频命中结果：字节与 JSON 冻结的交付文本 */
    private record AutoHit(byte[] bytes, String ttsText) {
    }

    /**
     * 读当前声音 + 完整目标语言的自动音频：先查未落盘的待保存字节，再读磁盘；
     * 必须有配套 JSON（ttsText 非空白、ttsLanguage 与当前目标语言严格相等），
     * 缺 JSON、不可读或格式不符均按未命中处理。扩展名按写入时的真实格式二选一
     * （MP3 与 OGG 各试一次）。
     */
    @Nullable
    private static AutoHit readAutoAudio(PrebuiltAudioStore store, AutoTarget target,
                                         String langTag, String targetLanguage) {
        // 1) 未落盘的待保存字节（同一存档周期内刚合成的音频立即可命中）
        for (String ext : new String[]{MP3_EXT, OGG_EXT}) {
            String audioName = target.baseName() + '.' + langTag + ext;
            for (PendingAudio pending : store.pendingWrites) {
                if (pending.relDir().equals(target.relDir()) && pending.audioName().equals(audioName)) {
                    if (targetLanguage != null && pending.ttsLanguage().equals(targetLanguage)
                            && !pending.ttsText().isBlank()) {
                        return new AutoHit(pending.audio(), pending.ttsText());
                    }
                    return null;
                }
            }
        }
        // 2) 磁盘：音频与 JSON 必须同时可读，JSON 语言必须与当前目标语言严格相等
        for (String ext : new String[]{MP3_EXT, OGG_EXT}) {
            Path audioFile = target.audioDir().resolve(target.baseName() + '.' + langTag + ext);
            Path jsonFile = target.audioDir().resolve(target.baseName() + '.' + langTag + JSON_EXT);
            byte[] audio = readBytes(audioFile);
            if (audio == null || detect(audio) == null) {
                continue;
            }
            String json = readString(jsonFile);
            if (json == null) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(json).getAsJsonObject();
                String ttsText = object.has("ttsText") && object.get("ttsText").isJsonPrimitive()
                        ? object.get("ttsText").getAsString() : null;
                String ttsLanguage = object.has("ttsLanguage") && object.get("ttsLanguage").isJsonPrimitive()
                        ? object.get("ttsLanguage").getAsString() : null;
                if (ttsText != null && !ttsText.isBlank() && targetLanguage != null
                        && targetLanguage.equals(ttsLanguage)) {
                    return new AutoHit(audio, ttsText);
                }
            } catch (RuntimeException e) {
                MaidSelfTalkMod.LOGGER.warn("Prebuilt audio sidecar {} unreadable ({}), treated as miss",
                        jsonFile, e.toString());
            }
        }
        return null;
    }

    /** 读人工音频：源 Markdown 同目录、同主名、无语言后缀的 .mp3/.ogg，MP3 优先；格式不符按未命中 */
    @Nullable
    private static byte[] readManualAudio(Path sourceMd) {
        String name = sourceMd.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return null;
        }
        String stem = name.substring(0, name.length() - 3);
        Path dir = sourceMd.getParent();
        if (dir == null) {
            return null;
        }
        byte[] mp3 = readAndDetect(dir.resolve(stem + MP3_EXT));
        if (mp3 != null) {
            return mp3;
        }
        return readAndDetect(dir.resolve(stem + OGG_EXT));
    }

    @Nullable
    private static byte[] readAndDetect(Path file) {
        byte[] data = readBytes(file);
        return data != null && detect(data) != null ? data : null;
    }

    @Nullable
    private static byte[] readBytes(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static String readString(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** custom/ 相对路径解析：归一化后必须仍在规定根目录内，防路径逃逸；解析后仍须是常规文件 */
    @Nullable
    private static Path resolveInside(Path root, String relPath) {
        try {
            Path resolved = root.resolve(relPath).normalize();
            if (!resolved.startsWith(root.normalize())) {
                return null;
            }
            return resolved;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
