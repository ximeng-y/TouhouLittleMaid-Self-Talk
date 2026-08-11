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
 * @param adminEnabled  管理员是否允许玩家配置（false 时客户端 UI 置灰）
 * @param globalEnabled 玩家全局开关（自己的所有女仆）
 * @param maidEnabled   请求的女仆单只有效值（全局 && 单只名单）
 */
public record SelfTalkConfigResponsePayload(boolean adminEnabled, boolean globalEnabled, boolean maidEnabled)
        implements CustomPacketPayload {

    public static final Type<SelfTalkConfigResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "config_response"));

    public static final StreamCodec<ByteBuf, SelfTalkConfigResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SelfTalkConfigResponsePayload::adminEnabled,
                    ByteBufCodecs.BOOL, SelfTalkConfigResponsePayload::globalEnabled,
                    ByteBufCodecs.BOOL, SelfTalkConfigResponsePayload::maidEnabled,
                    SelfTalkConfigResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
