package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * C2S：客户端提交玩家自话设置。
 *
 * @param maidUuid 目标女仆 UUID；为空表示设置"我的所有女仆"全局开关
 * @param enabled  是否触发自言自语
 */
public record SelfTalkConfigSetPayload(Optional<UUID> maidUuid, boolean enabled) implements CustomPacketPayload {

    public static final Type<SelfTalkConfigSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "config_set"));

    /** Optional&lt;UUID&gt; 手写编解码（writeBoolean + writeUUID），不依赖版本内置 codec */
    private static final StreamCodec<FriendlyByteBuf, Optional<UUID>> OPTIONAL_UUID = StreamCodec.of(
            (buf, value) -> {
                buf.writeBoolean(value.isPresent());
                value.ifPresent(uuid -> buf.writeUUID(uuid));
            },
            buf -> buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty());

    public static final StreamCodec<FriendlyByteBuf, SelfTalkConfigSetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    OPTIONAL_UUID, SelfTalkConfigSetPayload::maidUuid,
                    ByteBufCodecs.BOOL, SelfTalkConfigSetPayload::enabled,
                    SelfTalkConfigSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
