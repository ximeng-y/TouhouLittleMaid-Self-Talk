package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：服务端返回玩家自话设置。
 *
 * @param adminEnabled     管理员是否允许玩家配置（false 时客户端 UI 置灰）
 * @param selfTalkEnabled  该玩家的独立设置值
 */
public record SelfTalkConfigResponsePayload(boolean adminEnabled, boolean selfTalkEnabled)
        implements CustomPacketPayload {

    public static final Type<SelfTalkConfigResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "config_response"));

    public static final StreamCodec<ByteBuf, SelfTalkConfigResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SelfTalkConfigResponsePayload::adminEnabled,
                    ByteBufCodecs.BOOL, SelfTalkConfigResponsePayload::selfTalkEnabled,
                    SelfTalkConfigResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
