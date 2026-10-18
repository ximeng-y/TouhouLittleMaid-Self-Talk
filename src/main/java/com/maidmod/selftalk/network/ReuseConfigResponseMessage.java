package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.PlayerSettingsStore;
import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
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
 * <p>
 * issue #108 起同时带回匹配参数的服务器默认值、个人参数权限、服务端归一后的跟随状态
 * 与玩家的已存个人值；显示值由权威跟随状态在客户端选择，不再另传一组重复的「显示参数」。
 *
 * @param sessionId                  浮层会话 id（客户端据此丢弃关闭后或旧会话的迟到回包）
 * @param seq                        对应请求的序号
 * @param adminEnabled               管理员是否允许玩家配置（PLAYER_OPTION_ENABLED）
 * @param globalEnabled              玩家默认开关的已存偏好（缺省 false）
 * @param maidUuid                   请求携带的女仆 UUID（请求为 null 时此处也为 null）
 * @param maidStateId                女仆三态存储值：{@code inherit}/{@code on}/{@code off}；
 *                                   maidUuid 为 null 时为空串
 * @param serverAdmin                请求者是否可管理服务器参数（OP ≥ 2 或单人房主）
 * @param serverMinEligible          服务器默认最低可用条数
 * @param serverHitChance            服务器默认使用概率
 * @param playerParametersAllowed    是否允许玩家自定义参数
 * @param followingServer            服务端归一后的跟随状态（未开放权限时恒为 true）
 * @param playerMinEligible          玩家已存个人最低可用条数（不代表当前有效值）
 * @param playerHitChance            玩家已存个人使用概率（不代表当前有效值）
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
    private final boolean serverAdmin;
    private final int serverMinEligible;
    private final int serverHitChance;
    private final boolean playerParametersAllowed;
    private final boolean followingServer;
    private final int playerMinEligible;
    private final int playerHitChance;

    public ReuseConfigResponseMessage(UUID sessionId, long seq, boolean adminEnabled,
                                      boolean globalEnabled, UUID maidUuid, String maidStateId,
                                      boolean serverAdmin, int serverMinEligible, int serverHitChance,
                                      boolean playerParametersAllowed, boolean followingServer,
                                      int playerMinEligible, int playerHitChance) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.adminEnabled = adminEnabled;
        this.globalEnabled = globalEnabled;
        this.maidUuid = maidUuid;
        this.maidStateId = maidStateId;
        this.serverAdmin = serverAdmin;
        this.serverMinEligible = serverMinEligible;
        this.serverHitChance = serverHitChance;
        this.playerParametersAllowed = playerParametersAllowed;
        this.followingServer = followingServer;
        this.playerMinEligible = playerMinEligible;
        this.playerHitChance = playerHitChance;
    }

    /**
     * 组装权威快照（服务端主线程调用）。
     * <p>
     * 全局默认与单只三态都是<b>已存偏好</b>；管理员禁用不改写任何一项。
     * 单只三态按玩家 UUID 读该女仆的覆盖条目——非本人女仆在存档中本就无条目，
     * 自然读到「跟随默认」。
     * <p>
     * 未开放个人参数时跟随状态归一亮为 true：客户端据此强制显示服务器值并禁用个人编辑。
     */
    public static ReuseConfigResponseMessage snapshot(ServerPlayer player, UUID sessionId, long seq,
                                                      UUID maidUuid) {
        UUID playerUuid = player.getUUID();
        boolean global = PlayerSettingsStore.isPrebuiltReuseEnabled(player.server, playerUuid);
        String maidState = MAID_NONE;
        if (maidUuid != null) {
            Boolean override = PlayerSettingsStore.getPrebuiltReuseMaidOverride(player.server, playerUuid, maidUuid);
            maidState = override == null ? MAID_INHERIT : override ? MAID_ON : MAID_OFF;
        }
        boolean allowed = PlayerSettingsStore.arePrebuiltReusePlayerParametersAllowed(player.server);
        boolean follows = !allowed || PlayerSettingsStore.isPrebuiltReuseFollowingServer(player.server, playerUuid);
        return new ReuseConfigResponseMessage(sessionId, seq, Config.PLAYER_OPTION_ENABLED.get(),
                global, maidUuid, maidState,
                PlayerSettingsStore.isPrebuiltReuseAdmin(player),
                PlayerSettingsStore.getPrebuiltReuseServerMinEligible(player.server),
                PlayerSettingsStore.getPrebuiltReuseServerHitChance(player.server),
                allowed, follows,
                PlayerSettingsStore.getPrebuiltReusePlayerMinEligible(player.server, playerUuid),
                PlayerSettingsStore.getPrebuiltReusePlayerHitChance(player.server, playerUuid));
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

    public boolean serverAdmin() {
        return serverAdmin;
    }

    public int serverMinEligible() {
        return serverMinEligible;
    }

    public int serverHitChance() {
        return serverHitChance;
    }

    public boolean playerParametersAllowed() {
        return playerParametersAllowed;
    }

    public boolean followingServer() {
        return followingServer;
    }

    public int playerMinEligible() {
        return playerMinEligible;
    }

    public int playerHitChance() {
        return playerHitChance;
    }

    public static void encode(ReuseConfigResponseMessage msg, FriendlyByteBuf buf) {
        ReuseConfigRequestMessage.writeNullableUuid(buf, msg.sessionId);
        buf.writeVarLong(msg.seq);
        buf.writeBoolean(msg.adminEnabled);
        buf.writeBoolean(msg.globalEnabled);
        ReuseConfigRequestMessage.writeNullableUuid(buf, msg.maidUuid);
        buf.writeUtf(msg.maidStateId == null ? MAID_NONE : msg.maidStateId, MAX_STATE_LENGTH);
        buf.writeBoolean(msg.serverAdmin);
        buf.writeVarInt(msg.serverMinEligible);
        buf.writeVarInt(msg.serverHitChance);
        buf.writeBoolean(msg.playerParametersAllowed);
        buf.writeBoolean(msg.followingServer);
        buf.writeVarInt(msg.playerMinEligible);
        buf.writeVarInt(msg.playerHitChance);
    }

    public static ReuseConfigResponseMessage decode(FriendlyByteBuf buf) {
        return new ReuseConfigResponseMessage(ReuseConfigRequestMessage.readNullableUuid(buf), buf.readVarLong(),
                buf.readBoolean(), buf.readBoolean(), ReuseConfigRequestMessage.readNullableUuid(buf),
                buf.readUtf(MAX_STATE_LENGTH),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    /** 客户端：收到预制复用设置响应（会话/序号校验在客户端浮层内完成） */
    public static void handle(ReuseConfigResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SelfTalkPlayerSettingsClient.onReuseConfigResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
