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
 * C2S：客户端提交玩家 Tool 调用设置（结构同 InterChatConfigSetPayload）。
 *
 * @param maidUuid 目标女仆 UUID；为空表示设置"我的所有女仆"全局开关
 * @param enabled  是否开启 Tool 调用
 */
public record ToolConfigSetPayload(Optional<UUID> maidUuid, boolean enabled) implements CustomPacketPayload {

    public static final Type<ToolConfigSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "tool_config_set"));

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    public static final StreamCodec<FriendlyByteBuf, ToolConfigSetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.optional(UUID_STREAM_CODEC), ToolConfigSetPayload::maidUuid,
                    ByteBufCodecs.BOOL, ToolConfigSetPayload::enabled,
                    ToolConfigSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
