package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：服务端返回玩家 Tool 调用设置。
 *
 * @param adminEnabled     管理员是否允许玩家配置（PLAYER_OPTION_ENABLED，false 时界面置灰）
 * @param toolAdminEnabled 管理员是否开启 Tool 功能（TOOL_CALL_ENABLED）。
 *                         与 adminEnabled 分开返回，界面提示语才能区分「管理员关了玩家配置」和「管理员没开 Tool 功能」
 * @param globalEnabled    玩家 Tool 全局开关（缺省 false）
 * @param maidEnabled      请求的女仆单只有效值（全局 && 单只名单不含关闭项）
 */
public record ToolConfigResponsePayload(boolean adminEnabled, boolean toolAdminEnabled,
                                        boolean globalEnabled, boolean maidEnabled)
        implements CustomPacketPayload {

    public static final Type<ToolConfigResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "tool_config_response"));

    public static final StreamCodec<ByteBuf, ToolConfigResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, ToolConfigResponsePayload::adminEnabled,
                    ByteBufCodecs.BOOL, ToolConfigResponsePayload::toolAdminEnabled,
                    ByteBufCodecs.BOOL, ToolConfigResponsePayload::globalEnabled,
                    ByteBufCodecs.BOOL, ToolConfigResponsePayload::maidEnabled,
                    ToolConfigResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
