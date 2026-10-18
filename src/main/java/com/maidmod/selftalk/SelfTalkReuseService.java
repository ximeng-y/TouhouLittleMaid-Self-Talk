package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.mixin.CharacterSettingAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 预制复用的匹配与入库服务（实验性，默认关闭）：身份指纹、命中判定、条目构造。
 * <p>
 * 身份指纹只由「女仆名称 + 当前生效人设全文」决定（见 {@link #fingerprintOf}）；
 * 匹配按「key 严格相等」逐项比对快照环境表，全部阈值现读配置（热重载即时生效）；
 * 入库只发生在真实生成链上，复用投放的结果<b>绝不</b>回写库（防止同一句无限翻新时间戳）。
 * 全部方法仅服务端主线程调用。
 */
public final class SelfTalkReuseService {

    /** 入库时间格式（服务端本地时间；yy 非 YYYY、mm 为分钟，写错即时间序全乱） */
    private static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yy-MM-dd-HH:mm:ss");

    private SelfTalkReuseService() {
    }

    /**
     * 真实生成链留给回调的入库凭据：指纹、来源、当轮匹配环境表与上屏语言。
     * 命中复用时为 null（复用结果不入库）；生成流程中途失败也自然作废。
     */
    public record ArchiveData(String fingerprint, String source,
                              Map<String, String> matchEntries, String chatLanguage) {
    }

    /**
     * 身份指纹：女仆名称 + 当前生效人设全文的 SHA-256（小写十六进制）。
     * <p>
     * 名称取解析语言下的展示名（解析不出为 JSON null，不回退模型 id）；
     * 人设取「当前生效模式」的完整文本——四栏模式为模式标记 + 四栏全文，
     * 原版模式优先取 {@code customSetting}，否则取模型设定文件的<b>原始</b>全文
     * （{@code getSetting(maid, language)} 经占位符替换，同人因语言不同产出不同文本，
     * 不能作为稳定身份输入）。指纹中不混入主人/女仆 UUID、历史、召回结果、
     * 自定义 Prompt 或占位符替换结果——同一「同人同人设」必须落在同一组，
     * 改名改回旧名后也要能回到旧组。
     */
    public static String fingerprintOf(EntityMaid maid) {
        Component name = SelfTalkMaidNames.resolveName(maid, SelfTalkMaidNames.resolveQueryLanguage(maid));
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, name == null ? null : name.getString());
        appendField(canonical, personaText(maid));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // JVM 必带 SHA-256，此分支理论不可达；指纹失败即放弃复用，绝不阻断正常生成
            return null;
        }
    }

    /** 当前生效人设全文（四栏模式带模式标记与四栏定序；原版模式优先 customSetting，其次模型原始设定） */
    private static String personaText(EntityMaid maid) {
        MaidPersonaConfig config = MaidPersonaRuntime.configOf(maid);
        if (config.isStructured()) {
            MaidPersonaDraft draft = config.draft();
            return "structured\n"
                    + StringUtils.defaultString(draft.identity()) + "\n"
                    + StringUtils.defaultString(draft.personality()) + "\n"
                    + StringUtils.defaultString(draft.background()) + "\n"
                    + StringUtils.defaultString(draft.hobbies());
        }
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null) {
            return StringUtils.EMPTY;
        }
        if (StringUtils.isNotBlank(chatManager.customSetting)) {
            return chatManager.customSetting;
        }
        return chatManager.getSetting()
                .map(setting -> ((CharacterSettingAccessor) (Object) setting).maid_self_talk$rawSetting())
                .orElse(StringUtils.EMPTY);
    }

    /** 规范序列化：长度前缀定界（字段数与顺序固定在调用处），null 写作空段 */
    private static void appendField(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append("0:");
        } else {
            canonical.append(value.length()).append(':').append(value);
        }
        canonical.append('\n');
    }

    /**
     * 匹配：来源 + 指纹定位分组，先按上屏语言严格相等过滤，再按「key 严格相等」
     * 统计命中条目数（比对当前快照 {@code currentEnv} 与库内条目的 env 表）；
     * 命中数达到 {@code minMatchingContexts} 的成为候选；候选数达到
     * {@code minEligibleEntries} 才掷概率（{@code hitChancePercent}）；
     * 未命中概率或候选不足都返回 null，调用方继续正常生成链（同一快照复用，不重采集）。
     * <p>
     * 机器条目之外，人类可编辑条目（{@code custom/}，见 {@link PrebuiltCustomEntries}）
     * 并入同一候选池、共用同一组阈值：语言字段缺省即对所有语言生效；环境改用 AND 语义——
     * 条目写了的每个 key 都必须在当前快照中存在且严格相等（空 env 恒通过），
     * 避免手写一两个条件永远凑不够 {@code minMatchingContexts}。候选池合并后再统一下限与概率，
     * 不为纯人类条目放宽。
     * <p>
     * 不设额外否决条件（天气/历史/Prompt/近期用过都不看）：环境匹配本身就是情境筛选。
     */
    public static SelfTalkReuseStore.PrebuiltEntry tryMatch(MinecraftServer server, RandomSource random,
                                                            String source, String fingerprint,
                                                            Map<String, String> currentEnv, String chatLanguage,
                                                            EntityMaid maid) {
        if (fingerprint == null) {
            return null;
        }
        int minContexts = Config.PREBUILT_MIN_MATCHING_CONTEXTS.get();
        // issue #108：最低条数与概率改由主人个人参数解析（世界设置 + 受控覆盖）；
        // 每次匹配现读一组，不缓存到客户端或静态全局变量，改动只影响后续匹配
        PrebuiltReuseParameters parameters = PlayerSettingsStore.getPrebuiltReuseParametersForMaid(server, maid);
        int minEligible = parameters.minEligibleEntries();
        int hitChance = parameters.hitChancePercent();
        List<SelfTalkReuseStore.PrebuiltEntry> candidates = new ArrayList<>();
        for (SelfTalkReuseStore.PrebuiltEntry entry : SelfTalkReuseStore.of(server).entries(fingerprint, source)) {
            // 上屏文本与 TTS 文本都必须非空白：TLM 上游对两段都做空白校验，
            // 手改文件里缺 TTS 段的条目直接失去复用资格
            if (!entry.chatLanguage.equals(chatLanguage)
                    || entry.ttsText == null || entry.ttsText.isBlank()) {
                continue;
            }
            int matched = 0;
            for (Map.Entry<String, String> current : currentEnv.entrySet()) {
                String stored = entry.env.get(current.getKey());
                if (stored != null && stored.equals(current.getValue())) {
                    matched++;
                }
            }
            if (matched >= minContexts) {
                candidates.add(entry);
            }
        }
        // 人类条目：AND 语义 + 语言缺省通配；缺 TTS 段的已在解析时回退为 chatText，此处兜底同机器条目
        String maidName = resolveMaidDirName(maid);
        for (SelfTalkReuseStore.PrebuiltEntry entry : PrebuiltCustomEntries.of(server).entriesFor(maidName, source)) {
            if (entry.chatLanguage != null && !entry.chatLanguage.equals(chatLanguage)) {
                continue;
            }
            if (entry.ttsText == null || entry.ttsText.isBlank()) {
                continue;
            }
            if (!allWrittenKeysEqual(entry.env, currentEnv)) {
                continue;
            }
            candidates.add(entry);
        }
        if (candidates.size() < minEligible) {
            return null;
        }
        if (random.nextInt(100) >= hitChance) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * 人类条目的环境匹配（AND）：写入的每个 key 都必须在当前快照中存在且严格相等；
     * 空 env（无头条目）恒通过。key 在快照中缺失视为不相等。
     */
    static boolean allWrittenKeysEqual(Map<String, String> written, Map<String, String> current) {
        for (Map.Entry<String, String> writtenEntry : written.entrySet()) {
            String now = current.get(writtenEntry.getKey());
            if (now == null || !now.equals(writtenEntry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** 具名目录匹配用的女仆显示名：沿用 {@link SelfTalkMaidNames} 解析顺序，解析不出返回 null（只吃全局条目） */
    private static String resolveMaidDirName(EntityMaid maid) {
        Component name = SelfTalkMaidNames.resolveName(maid, SelfTalkMaidNames.resolveQueryLanguage(maid));
        return name == null ? null : name.getString();
    }

    /**
     * 构造入库凭据（触发时调用，随请求冻结）；预制复用关闭时不产生凭据。
     * 匹配环境表使用当轮快照的「最终注入」表（欢迎语为固定分类 + 抽中情境）。
     */
    public static ArchiveData archiveData(String fingerprint, String source,
                                          Map<String, String> matchEntries, String chatLanguage) {
        return new ArchiveData(fingerprint, source, Map.copyOf(matchEntries), chatLanguage);
    }

    /**
     * 入库：真实生成链在回复验证通过后调用一次。条目标识 entryId 由本次真实生成入库时
     * 用随机 UUID 创建（复用命中不创建新条目，复用投放绝不入库）；入库时间取服务端本地
     * 时间当下值；ttsLanguage 记录入库时聊天管理器的 TTS 语言（复用侧据此决定是否需要翻译）。
     */
    public static void appendEntry(MinecraftServer server, ArchiveData data,
                                   String chatText, String ttsText, String ttsLanguage) {
        String createdAt = LocalDateTime.now().format(CREATED_AT_FORMAT);
        SelfTalkReuseStore.PrebuiltEntry entry = new SelfTalkReuseStore.PrebuiltEntry(
                UUID.randomUUID(), createdAt, data.matchEntries(), data.chatLanguage(),
                chatText, ttsLanguage, ttsText, SelfTalkReuseStore.Origin.MACHINE);
        SelfTalkReuseStore.of(server).append(data.fingerprint(), data.source(), entry,
                Config.PREBUILT_MAX_ENTRIES_PER_SOURCE.get());
    }
}
