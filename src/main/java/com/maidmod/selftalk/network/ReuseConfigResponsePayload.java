package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * S2C：预制复用设置的权威快照。
 * <p>
 * 回传的是<b>已存偏好</b>：管理员禁用时界面要能显示玩家原来的选择（整页只读），
 * 功能恢复后原样生效——与全局开关同口径，不回传被管理员门控后的有效值。
 * 单只三态为「跟随默认 / 显式开 / 显式关」的存储态，全局默认与单只覆盖的叠加
 * 由界面显示时自行组合。
 *
 * @param sessionId     浮层会话 id（客户端据此丢弃关闭后或旧会话的迟到回包）
 * @param seq           对应请求的序号
 * @param adminEnabled  管理员是否允许玩家配置（{@code PLAYER_OPTION_ENABLED}）
 * @param globalEnabled 玩家默认开关的已存偏好（缺省 false）
 * @param maidUuid      请求携带的女仆 UUID（请求为 null 时此处也为 null）
 * @param maidStateId   女仆三态存储值：{@code inherit}/{@code on}/{@code off}；
 *                      maidUuid 为 null 时为空串
 */
public record ReuseConfigResponsePayload(UUID sessionId, long seq, boolean adminEnabled,
                                         boolean globalEnabled, UUID maidUuid, String maidStateId)
        implements CustomPacketPayload {

    /** 单只覆盖存储值：跟随默认（无覆盖条目） */
    public static final String MAID_INHERIT = "inherit";
    /** 单只覆盖存储值：显式开 */
    public static final String MAID_ON = "on";
    /** 单只覆盖存储值：显式关 */
    public static final String MAID_OFF = "off";
    /** maidUuid 为空时的占位值 */
    public static final String MAID_NONE = "";

    public static final Type<ReuseConfigResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID,
                    "reuse_config_response"));

    /** 三态存储值长度上限（与 Set 包的编解码习惯一致，防超长串撑包） */
    private static final int MAX_STATE_LENGTH = 16;

    public static final StreamCodec<FriendlyByteBuf, ReuseConfigResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ReuseConfigRequestPayload.NULLABLE_UUID_STREAM_CODEC, ReuseConfigResponsePayload::sessionId,
                    ByteBufCodecs.VAR_LONG, ReuseConfigResponsePayload::seq,
                    ByteBufCodecs.BOOL, ReuseConfigResponsePayload::adminEnabled,
                    ByteBufCodecs.BOOL, ReuseConfigResponsePayload::globalEnabled,
                    ReuseConfigRequestPayload.NULLABLE_UUID_STREAM_CODEC, ReuseConfigResponsePayload::maidUuid,
                    ByteBufCodecs.stringUtf8(MAX_STATE_LENGTH), ReuseConfigResponsePayload::maidStateId,
                    ReuseConfigResponsePayload::new);

    /**
     * 组装权威快照（服务端主线程调用）。
     * <p>
     * 全局默认与单只三态都是<b>已存偏好</b>；管理员禁用不改写任何一项。
     * 单只三态按玩家 UUID 读该女仆的覆盖条目——非本人女仆在存档中本就无条目，
     * 自然读到「跟随默认」。
     */
    public static ReuseConfigResponsePayload snapshot(MinecraftServer server, UUID playerUuid,
                                                      UUID sessionId, long seq, UUID maidUuid) {
        boolean global = PlayerSettingsStore.isPrebuiltReuseEnabled(server, playerUuid);
        String maidState = MAID_NONE;
        if (maidUuid != null) {
            Boolean override = PlayerSettingsStore.getPrebuiltReuseMaidOverride(server, playerUuid, maidUuid);
            maidState = override == null ? MAID_INHERIT : override ? MAID_ON : MAID_OFF;
        }
        return new ReuseConfigResponsePayload(sessionId, seq, Config.PLAYER_OPTION_ENABLED.get(),
                global, maidUuid, maidState);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
