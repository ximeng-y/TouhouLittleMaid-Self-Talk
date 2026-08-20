package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：服务端返回互聊设置。
 */
public record InterChatConfigResponsePayload(boolean adminEnabled, boolean globalEnabled, boolean maidEnabled)
        implements CustomPacketPayload {

    public static final Type<InterChatConfigResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "inter_chat_config_response"));

    public static final StreamCodec<ByteBuf, InterChatConfigResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, InterChatConfigResponsePayload::adminEnabled,
                    ByteBufCodecs.BOOL, InterChatConfigResponsePayload::globalEnabled,
                    ByteBufCodecs.BOOL, InterChatConfigResponsePayload::maidEnabled,
                    InterChatConfigResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
