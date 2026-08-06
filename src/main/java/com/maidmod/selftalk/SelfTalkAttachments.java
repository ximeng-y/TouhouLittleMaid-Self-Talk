package com.maidmod.selftalk;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 玩家独立设置的数据附件（挂在玩家实体上，随实体存档/同步）。
 */
public final class SelfTalkAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(Registries.ATTACHMENT_TYPE, MaidSelfTalkMod.MODID);

    /** 玩家独立设置：自己的女仆是否触发自言自语（默认启用） */
    public static final Supplier<AttachmentType<Boolean>> SELF_TALK_ENABLED =
            ATTACHMENT_TYPES.register("self_talk_enabled",
                    () -> AttachmentType.builder(() -> true).serialize(Codec.BOOL).build());

    private SelfTalkAttachments() {
    }
}
