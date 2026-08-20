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
 * C2S：客户端提交互聊设置。
 */
public record InterChatConfigSetPayload(Optional<UUID> maidUuid, boolean enabled) implements CustomPacketPayload {

    public static final Type<InterChatConfigSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "inter_chat_config_set"));

    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    public static final StreamCodec<FriendlyByteBuf, InterChatConfigSetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.optional(UUID_STREAM_CODEC), InterChatConfigSetPayload::maidUuid,
                    ByteBufCodecs.BOOL, InterChatConfigSetPayload::enabled,
                    InterChatConfigSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
