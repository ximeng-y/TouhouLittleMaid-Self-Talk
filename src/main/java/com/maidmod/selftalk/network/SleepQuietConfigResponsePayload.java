package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：服务端返回玩家「睡觉时安静」设置。
 * <p>
 * 该设置不受管理员 Config.PLAYER_OPTION_ENABLED 控制，故无 adminEnabled 字段——
 * 客户端 UI 中此组按钮恒可操作。
 *
 * @param globalEnabled 玩家全局开关（自己所有女仆；缺省 true = 睡觉时安静）
 * @param maidEnabled   请求的女仆单只有效值（全局安静，或全局允许说话时单只名单含该女仆）
 */
public record SleepQuietConfigResponsePayload(boolean globalEnabled, boolean maidEnabled)
        implements CustomPacketPayload {

    public static final Type<SleepQuietConfigResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "sleep_quiet_config_response"));

    public static final StreamCodec<ByteBuf, SleepQuietConfigResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SleepQuietConfigResponsePayload::globalEnabled,
                    ByteBufCodecs.BOOL, SleepQuietConfigResponsePayload::maidEnabled,
                    SleepQuietConfigResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
