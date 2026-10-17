package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.PlayerSettingsStore;
import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

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
 * @param adminEnabled  管理员是否允许玩家配置（PLAYER_OPTION_ENABLED）
 * @param globalEnabled 玩家默认开关的已存偏好（缺省 false）
 * @param maidUuid      请求携带的女仆 UUID（请求为 null 时此处也为 null）
 * @param maidStateId   女仆三态存储值：{@code inherit}/{@code on}/{@code off}；
 *                      maidUuid 为 null 时为空串
 */
public class ReuseConfigResponseMessage {

    /** 单只覆盖存储值：跟随默认（无覆盖条目） */
    public static final String MAID_INHERIT = "inherit";
    /** 单只覆盖存储值：显式开 */
    public static final String MAID_ON = "on";
    /** 单只覆盖存储值：显式关 */
    public static final String MAID_OFF = "off";
    /** maidUuid 为空时的占位值 */
    public static final String MAID_NONE = "";

    /** 三态存储值长度上限（与其它设置包的编解码习惯一致，防超长串撑包） */
    private static final int MAX_STATE_LENGTH = 16;

    private final UUID sessionId;
    private final long seq;
    private final boolean adminEnabled;
    private final boolean globalEnabled;
    private final UUID maidUuid;
    private final String maidStateId;

    public ReuseConfigResponseMessage(UUID sessionId, long seq, boolean adminEnabled,
                                      boolean globalEnabled, UUID maidUuid, String maidStateId) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.adminEnabled = adminEnabled;
        this.globalEnabled = globalEnabled;
        this.maidUuid = maidUuid;
        this.maidStateId = maidStateId;
    }

    /**
     * 组装权威快照（服务端主线程调用）。
     * <p>
     * 全局默认与单只三态都是<b>已存偏好</b>；管理员禁用不改写任何一项。
     * 单只三态按玩家 UUID 读该女仆的覆盖条目——非本人女仆在存档中本就无条目，
     * 自然读到「跟随默认」。
     */
    public static ReuseConfigResponseMessage snapshot(MinecraftServer server, UUID playerUuid,
                                                      UUID sessionId, long seq, UUID maidUuid) {
        boolean global = PlayerSettingsStore.isPrebuiltReuseEnabled(server, playerUuid);
        String maidState = MAID_NONE;
        if (maidUuid != null) {
            Boolean override = PlayerSettingsStore.getPrebuiltReuseMaidOverride(server, playerUuid, maidUuid);
            maidState = override == null ? MAID_INHERIT : override ? MAID_ON : MAID_OFF;
        }
        return new ReuseConfigResponseMessage(sessionId, seq, Config.PLAYER_OPTION_ENABLED.get(),
                global, maidUuid, maidState);
    }

    public UUID sessionId() {
        return sessionId;
    }

    public long seq() {
        return seq;
    }

    public boolean adminEnabled() {
        return adminEnabled;
    }

    public boolean globalEnabled() {
        return globalEnabled;
    }

    public UUID maidUuid() {
        return maidUuid;
    }

    public String maidStateId() {
        return maidStateId;
    }

    public static void encode(ReuseConfigResponseMessage msg, FriendlyByteBuf buf) {
        ReuseConfigRequestMessage.writeNullableUuid(buf, msg.sessionId);
        buf.writeVarLong(msg.seq);
        buf.writeBoolean(msg.adminEnabled);
        buf.writeBoolean(msg.globalEnabled);
        ReuseConfigRequestMessage.writeNullableUuid(buf, msg.maidUuid);
        buf.writeUtf(msg.maidStateId == null ? MAID_NONE : msg.maidStateId, MAX_STATE_LENGTH);
    }

    public static ReuseConfigResponseMessage decode(FriendlyByteBuf buf) {
        return new ReuseConfigResponseMessage(ReuseConfigRequestMessage.readNullableUuid(buf), buf.readVarLong(),
                buf.readBoolean(), buf.readBoolean(), ReuseConfigRequestMessage.readNullableUuid(buf),
                buf.readUtf(MAX_STATE_LENGTH));
    }

    /** 客户端：收到预制复用设置响应（会话/序号校验在客户端浮层内完成） */
    public static void handle(ReuseConfigResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SelfTalkPlayerSettingsClient.onReuseConfigResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
