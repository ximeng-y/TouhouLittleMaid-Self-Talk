package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：客户端提交玩家自话设置。
 *
 * @param selfTalkEnabled 是否触发自言自语
 */
public record SelfTalkConfigSetPayload(boolean selfTalkEnabled) implements CustomPacketPayload {

    public static final Type<SelfTalkConfigSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "config_set"));

    public static final StreamCodec<ByteBuf, SelfTalkConfigSetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SelfTalkConfigSetPayload::selfTalkEnabled,
                    SelfTalkConfigSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
