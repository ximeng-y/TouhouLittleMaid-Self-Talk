package com.maidmod.selftalk;

import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.HashMap;
import java.util.Map;
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

    /** builder 泛型需显式指定（HashMap::new 会同时匹配 Supplier 与 Function 重载） */
    private static AttachmentType<Map<String, Boolean>> buildMaidOverrides() {
        return AttachmentType.builder((Supplier<Map<String, Boolean>>) HashMap::new)
                .serialize(Codec.unboundedMap(Codec.STRING, Codec.BOOL))
                .build();
    }

    private SelfTalkAttachments() {
    }
}
