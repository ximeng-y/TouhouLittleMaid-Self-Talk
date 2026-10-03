package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.HistoryContextMode;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：提交「历史上下文模式」（玩家全局偏好，作用于该玩家名下全部女仆）。
 * <p>
 * 发送的是<b>目标绝对模式</b>而不是「循环一次」命令：丢包／乱序时不会把模式切到意料之外的状态，
 * 客户端也无需依赖本地推断链条。包内不携带 player UUID 或 maid UUID——
 * 玩家身份一律取连接上的 {@link ServerPlayer}，语义上也没有女仆维度（总开关按玩家算）。
 *
 * @param sessionId 浮层会话 id（与 32 项设置共用同一个会话）
 * @param seq       该会话内的请求序号
 * @param modeId    目标模式存储值
 */
public class HistoryContextModeSetMessage {

    /** 模式存储值在包内的最大长度（与模式枚举的 id 长度对齐，超长包直接截断而不是撑大分配） */
    public static final int MAX_MODE_LENGTH = 32;

    private final UUID sessionId;
    private final long seq;
    private final String modeId;

    public HistoryContextModeSetMessage(UUID sessionId, long seq, String modeId) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.modeId = modeId;
    }

    public static void encode(HistoryContextModeSetMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sessionId);
        buf.writeVarLong(msg.seq);
        buf.writeUtf(msg.modeId == null ? "" : msg.modeId, MAX_MODE_LENGTH);
    }

    public static HistoryContextModeSetMessage decode(FriendlyByteBuf buf) {
        return new HistoryContextModeSetMessage(buf.readUUID(), buf.readVarLong(), buf.readUtf(MAX_MODE_LENGTH));
    }

    /**
     * 服务端：保存模式（纵深防御，不信任客户端）。
     * <p>
     * 与 32 项设置共用同一个浮层会话与同一套限流；管理员允许时保存，不允许时只回快照、<b>不</b>改存档——
     * 玩家原来的选择必须保留，功能恢复后继续生效。非法模式值不保存，直接回当前权威值。
     * 除被限流直接丢弃外，一律回完整环境上下文快照（布尔值、模式与管理员状态作为同一快照应用）。
     */
    public static void handle(HistoryContextModeSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null || msg.sessionId == null
                    || !SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                return;
            }
            HistoryContextMode mode = HistoryContextMode.fromId(msg.modeId);
            if (mode != null && Config.PLAYER_OPTION_ENABLED.get()) {
                PlayerSettingsStore.setHistoryContextMode(serverPlayer.server, serverPlayer.getUUID(), mode);
            }
            SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    EnvironmentContextConfigResponseMessage.snapshot(
                            serverPlayer.server, serverPlayer.getUUID(), msg.sessionId, msg.seq));
        });
        ctx.get().setPacketHandled(true);
    }
}
