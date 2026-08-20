package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：客户端请求互聊设置。
 */
public record InterChatConfigRequestPayload(UUID maidUuid) implements CustomPacketPayload {

    public static final Type<InterChatConfigRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "inter_chat_config_request"));

    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    public static final StreamCodec<FriendlyByteBuf, InterChatConfigRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUID_STREAM_CODEC, InterChatConfigRequestPayload::maidUuid,
                    InterChatConfigRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
