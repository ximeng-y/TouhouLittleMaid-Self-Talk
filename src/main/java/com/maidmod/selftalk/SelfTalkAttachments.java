package com.maidmod.selftalk;

import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 玩家独立设置的数据附件（挂在玩家实体上，随实体存档/同步）。
 */
public final class SelfTalkAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MaidSelfTalkMod.MODID);

    // ===== 旧版玩家实体附件(1.1.1 及更早,1.1.2 起不再读写) =====
    // 仅保留注册以兼容旧档玩家 NBT 反序列化;1.1.2 起玩家登录时经 SelfTalkMigration
    // 一次性迁移到下方 Level 附件并 removeData,此后这些类型只有迁移读点使用。

    /** 玩家独立设置：自己的女仆是否触发自言自语（默认启用）（旧版宿主=玩家实体，仅迁移读用） */
    public static final Supplier<AttachmentType<Boolean>> SELF_TALK_ENABLED =
            ATTACHMENT_TYPES.register("self_talk_enabled",
                    () -> AttachmentType.builder(() -> true).serialize(Codec.BOOL).build());

    /**
     * 单只关闭名单：女仆 UUID 字符串 -> 是否关闭（仅存关闭项，值恒 false，不存 true）。
     * 键不存在 = 跟随全局设置；恢复单只时 remove 键，名单不膨胀。
     */
    public static final Supplier<AttachmentType<Map<String, Boolean>>> SELF_TALK_MAID_OVERRIDES =
            ATTACHMENT_TYPES.register("self_talk_maid_overrides", SelfTalkAttachments::buildMaidOverrides);

    /** 玩家独立设置：自己的女仆是否触发互聊（默认启用） */
    public static final Supplier<AttachmentType<Boolean>> INTER_CHAT_ENABLED =
            ATTACHMENT_TYPES.register("inter_chat_enabled",
                    () -> AttachmentType.builder(() -> true).serialize(Codec.BOOL).build());

    /** 互聊单只关闭名单 */
    public static final Supplier<AttachmentType<Map<String, Boolean>>> INTER_CHAT_MAID_OVERRIDES =
            ATTACHMENT_TYPES.register("inter_chat_maid_overrides", SelfTalkAttachments::buildMaidOverrides);

    // ===== 服务器级玩家设置(挂 overworld Level 附件,1.1.2 起) =====
    // 玩家设置宿主从玩家实体迁移到 overworld:主人离线(态 2)时设置同样生效,
    // 且不再随玩家死亡克隆/玩家文件残留,数据随世界存档走。
    // 当前读点全部经 PlayerSettingsStore,切勿直接 getData 本组附件。

    /** 自话全局开关(Level):玩家 UUID -> 是否触发自言自语;缺失 = 启用 */
    public static final Supplier<AttachmentType<Map<String, Boolean>>> LEVEL_SELF_TALK_ENABLED =
            ATTACHMENT_TYPES.register("level_self_talk_enabled",
                    () -> AttachmentType.builder((Supplier<Map<String, Boolean>>) HashMap::new)
                            .serialize(Codec.unboundedMap(Codec.STRING, Codec.BOOL)).build());

    /** 自话单只关闭名单(Level):玩家 UUID -> 女仆 UUID -> false(仅存关闭项) */
    public static final Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> LEVEL_SELF_TALK_MAID_OVERRIDES =
            ATTACHMENT_TYPES.register("level_self_talk_maid_overrides", SelfTalkAttachments::buildLevelMaidOverrides);

    /** 互聊全局开关(Level):玩家 UUID -> 是否触发互聊;缺失 = 启用 */
    public static final Supplier<AttachmentType<Map<String, Boolean>>> LEVEL_INTER_CHAT_ENABLED =
            ATTACHMENT_TYPES.register("level_inter_chat_enabled",
                    () -> AttachmentType.builder((Supplier<Map<String, Boolean>>) HashMap::new)
                            .serialize(Codec.unboundedMap(Codec.STRING, Codec.BOOL)).build());

    /** 互聊单只关闭名单(Level):玩家 UUID -> 女仆 UUID -> false(仅存关闭项) */
    public static final Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> LEVEL_INTER_CHAT_MAID_OVERRIDES =
            ATTACHMENT_TYPES.register("level_inter_chat_maid_overrides", SelfTalkAttachments::buildLevelMaidOverrides);

    /** 睡觉时安静全局开关(Level):玩家 UUID -> 是否睡觉时安静;缺失 = 安静(true)。
     *  注意与自话/互聊极性相反:true = 女仆睡觉时不说话,且此时单只名单不可配置。 */
    public static final Supplier<AttachmentType<Map<String, Boolean>>> LEVEL_SLEEP_QUIET_ENABLED =
            ATTACHMENT_TYPES.register("level_sleep_quiet_enabled",
                    () -> AttachmentType.builder((Supplier<Map<String, Boolean>>) HashMap::new)
                            .serialize(Codec.unboundedMap(Codec.STRING, Codec.BOOL)).build());

    /** 睡觉时安静单只名单(Level):玩家 UUID -> 女仆 UUID -> true(仅存安静项,值恒 true)。
     *  仅在全局开关为 false(允许说话)时生效:玩家为个别女仆单独开启「睡觉时安静」。 */
    public static final Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> LEVEL_SLEEP_QUIET_MAID_OVERRIDES =
            ATTACHMENT_TYPES.register("level_sleep_quiet_maid_overrides", SelfTalkAttachments::buildLevelMaidOverrides);

    /** 自定义 Prompt 全局段(Level):玩家 UUID -> Prompt 文本;空串条目不落盘(缺失 = 未填写) */
    public static final Supplier<AttachmentType<Map<String, String>>> LEVEL_CUSTOM_PROMPT_GLOBAL =
            ATTACHMENT_TYPES.register("level_custom_prompt_global",
                    () -> AttachmentType.builder((Supplier<Map<String, String>>) HashMap::new)
                            .serialize(Codec.unboundedMap(Codec.STRING, Codec.STRING)).build());

    /** 自定义 Prompt 单只段(Level):玩家 UUID -> 女仆 UUID -> Prompt 文本;空串条目不落盘 */
    public static final Supplier<AttachmentType<Map<String, Map<String, String>>>> LEVEL_CUSTOM_PROMPT_MAID =
            ATTACHMENT_TYPES.register("level_custom_prompt_maid",
                    SelfTalkAttachments::buildLevelMaidStringMap);

    /** 自定义 Prompt「全局覆盖单只」开关(Level):玩家 UUID -> true(仅存 true 项,缺失 = 关闭) */
    public static final Supplier<AttachmentType<Map<String, Boolean>>> LEVEL_CUSTOM_PROMPT_OVERRIDE =
            ATTACHMENT_TYPES.register("level_custom_prompt_override",
                    () -> AttachmentType.builder((Supplier<Map<String, Boolean>>) HashMap::new)
                            .serialize(Codec.unboundedMap(Codec.STRING, Codec.BOOL)).build());

    /** Tool 调用玩家全局开关(Level):玩家 UUID -> true(仅存 true 项,缺失 = 关闭——Tool 玩家默认关闭) */
    public static final Supplier<AttachmentType<Map<String, Boolean>>> LEVEL_TOOL_CALL_ENABLED =
            ATTACHMENT_TYPES.register("level_tool_call_enabled",
                    () -> AttachmentType.builder((Supplier<Map<String, Boolean>>) HashMap::new)
                            .serialize(Codec.unboundedMap(Codec.STRING, Codec.BOOL)).build());

    /** Tool 调用单只关闭名单(Level):玩家 UUID -> 女仆 UUID -> false(仅存关闭项,缺失 = 跟随全局) */
    public static final Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> LEVEL_TOOL_CALL_MAID_OVERRIDES =
            ATTACHMENT_TYPES.register("level_tool_call_maid_overrides", SelfTalkAttachments::buildLevelMaidOverrides);

    /**
     * 环境上下文模式覆盖(Level):玩家 UUID -> 条目标 key -> 模式存储值
     * ({@code random} / {@code always} / {@code never})。
     * <p>
     * 只存与目录默认模式不同的项：原随机项设回 random、原固定项设回 always 都删键；
     * 原固定项改成 random 必须落盘（{@link EnvironmentContextOption#isOverride}）。
     * 内层表清空后删外层键——沿用本组附件的轻量化惯例。
     * <p>
     * 管理员禁用、提供者未注册都不是非法 key：这些偏好必须保留，功能恢复后继续生效。
     */
    public static final Supplier<AttachmentType<Map<String, Map<String, String>>>> LEVEL_ENVIRONMENT_CONTEXT_MODES =
            ATTACHMENT_TYPES.register("level_environment_context_modes",
                    SelfTalkAttachments::buildLevelMaidStringMap);

    /**
     * 环境信息自然语言化开关(Level):玩家 UUID -> true（仅存 true 项，缺失 = 关闭）。
     * <p>
     * 实验性模式，默认关闭；只存开启项，关闭时删键，不留无意义的 false 覆盖。
     * 管理员关闭玩家配置时有效值为关闭，但这里的已存偏好必须保留，功能恢复后继续生效。
     */
    public static final Supplier<AttachmentType<Map<String, Boolean>>> LEVEL_ENVIRONMENT_CONTEXT_NATURAL_LANGUAGE =
            ATTACHMENT_TYPES.register("level_environment_context_natural_language",
                    () -> AttachmentType.builder((Supplier<Map<String, Boolean>>) HashMap::new)
                            .serialize(Codec.unboundedMap(Codec.STRING, Codec.BOOL)).build());

    /**
     * 历史上下文模式(Level):玩家 UUID -> 模式存储值({@code compact} / {@code retrieval})。
     * <p>
     * 只存<b>非默认</b>模式：默认 {@code full} 不落盘，玩家切回全量时删键——
     * 与自然语言化开关同一哲学，不留无意义的默认值覆盖。
     * 管理员关闭玩家配置时有效值为 full，但这里的已存偏好必须保留，功能恢复后继续生效。
     */
    public static final Supplier<AttachmentType<Map<String, String>>> LEVEL_HISTORY_CONTEXT_MODE =
            ATTACHMENT_TYPES.register("level_history_context_mode",
                    () -> AttachmentType.builder((Supplier<Map<String, String>>) HashMap::new)
                            .serialize(Codec.unboundedMap(Codec.STRING, Codec.STRING)).build());

    /**
     * 女仆自话回复指纹集（来源判定用，挂女仆实体）。
     * <p>
     * 指纹 = (role, message, gameTime) 三元组拼接串。TLM 历史 deque 中自话回复与主人聊天回复
     * 都是 ASSISTANT、形态相同（"Part1---Part2"），只有登记了指纹才能区分来源。
     * 互聊消息不进历史（仅内存窗口），无需指纹。
     * <p>
     * 跨线程：登记与删除已统一投递到服务端主线程（AttachmentHolder 内部的
     * IdentityHashMap 非线程安全，不能从 LLM 响应线程直接读写附件）；
     * 集合仍用并发集兜底（平台反序列化等潜在并发路径）。
     */
    public static final Supplier<AttachmentType<Set<String>>> SELF_TALK_FINGERPRINTS =
            ATTACHMENT_TYPES.register("self_talk_fingerprints",
                    () -> AttachmentType.builder((Supplier<Set<String>>) ConcurrentHashMap::newKeySet)
                            .serialize(stringSetCodec()).build());

    /**
     * 老会话（首次启用段标签时的历史快照）指纹集：命中即段外原样（不进 XML）。
     * 一次性初始化（{@link #SEGMENT_LEGACY_INITIALIZED} 标记），永不重初始化、不随压缩删除。
     */
    public static final Supplier<AttachmentType<Set<String>>> LEGACY_SEGMENT_FINGERPRINTS =
            ATTACHMENT_TYPES.register("legacy_segment_fingerprints",
                    () -> AttachmentType.builder((Supplier<Set<String>>) ConcurrentHashMap::newKeySet)
                            .serialize(stringSetCodec()).build());

    /** 老会话快照是否已初始化（一次性，true 后不再变更） */
    public static final Supplier<AttachmentType<Boolean>> SEGMENT_LEGACY_INITIALIZED =
            ATTACHMENT_TYPES.register("segment_legacy_initialized",
                    () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).build());

    /** Set<String> codec：解码为并发集（跨线程读写安全），编码为列表 */
    private static Codec<Set<String>> stringSetCodec() {
        return Codec.STRING.listOf().xmap(
                list -> {
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    set.addAll(list);
                    return set;
                },
                set -> new ArrayList<>(set));
    }

    /** builder 泛型需显式指定（HashMap::new 会同时匹配 Supplier 与 Function 重载） */
    private static AttachmentType<Map<String, Boolean>> buildMaidOverrides() {
        return AttachmentType.builder((Supplier<Map<String, Boolean>>) HashMap::new)
                .serialize(Codec.unboundedMap(Codec.STRING, Codec.BOOL))
                .build();
    }

    /** Level 两级名单的 builder：外层玩家 UUID -> 内层女仆 UUID -> 布尔项 */
    private static AttachmentType<Map<String, Map<String, Boolean>>> buildLevelMaidOverrides() {
        return AttachmentType.builder((Supplier<Map<String, Map<String, Boolean>>>) HashMap::new)
                .serialize(Codec.unboundedMap(Codec.STRING,
                        Codec.unboundedMap(Codec.STRING, Codec.BOOL)))
                .build();
    }

    /** Level 两级名单的 builder(字符串版)：外层玩家 UUID -> 内层女仆 UUID -> Prompt 文本 */
    private static AttachmentType<Map<String, Map<String, String>>> buildLevelMaidStringMap() {
        return AttachmentType.builder((Supplier<Map<String, Map<String, String>>>) HashMap::new)
                .serialize(Codec.unboundedMap(Codec.STRING,
                        Codec.unboundedMap(Codec.STRING, Codec.STRING)))
                .build();
    }

    private SelfTalkAttachments() {
    }
}
