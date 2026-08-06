package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：客户端请求当前玩家自话设置（含管理员是否允许玩家配置）。
 */
public record SelfTalkConfigRequestPayload() implements CustomPacketPayload {

    public static final Type<SelfTalkConfigRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "config_request"));

    public static final StreamCodec<ByteBuf, SelfTalkConfigRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new SelfTalkConfigRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
