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

    /** 玩家独立设置：自己的女仆是否触发自言自语（默认启用） */
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

    /**
     * 女仆自话回复指纹集（来源判定用，挂女仆实体）。
     * <p>
     * 指纹 = (role, message, gameTime) 三元组拼接串。TLM 历史 deque 中自话回复与主人聊天回复
     * 都是 ASSISTANT、形态相同（"Part1---Part2"），只有登记了指纹才能区分来源。
     * 互聊消息不进历史（仅内存窗口），无需指纹。
     * <p>
     * 跨线程：自话回复在 LLM 响应线程登记，wrap 在服务端主线程读取——
     * 解码/默认值均用并发集，避免与 TLM 异步压缩回调（同为响应线程删指纹）竞争。
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

    private SelfTalkAttachments() {
    }
}
