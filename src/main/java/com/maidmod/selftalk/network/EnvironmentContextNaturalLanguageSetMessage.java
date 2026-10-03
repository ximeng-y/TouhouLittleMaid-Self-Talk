package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：提交「环境信息自然语言化」总开关（玩家全局偏好，作用于该玩家名下全部女仆）。
 * <p>
 * 发送的是<b>目标绝对布尔值</b>而不是「切换一次」命令：丢包／乱序时不会把开关切到意料之外的状态，
 * 客户端也无需依赖本地推断链条。包内不携带 player UUID 或 maid UUID——
 * 玩家身份一律取连接上的 {@link ServerPlayer}，语义上也没有女仆维度（总开关按玩家算）。
 *
 * @param sessionId 浮层会话 id（与 32 项设置共用同一个会话）
 * @param seq       该会话内的请求序号
 * @param enabled   目标状态（绝对布尔值）
 */
public class EnvironmentContextNaturalLanguageSetMessage {

    private final UUID sessionId;
    private final long seq;
    private final boolean enabled;

    public EnvironmentContextNaturalLanguageSetMessage(UUID sessionId, long seq, boolean enabled) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.enabled = enabled;
    }

    public static void encode(EnvironmentContextNaturalLanguageSetMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sessionId);
        buf.writeVarLong(msg.seq);
        buf.writeBoolean(msg.enabled);
    }

    public static EnvironmentContextNaturalLanguageSetMessage decode(FriendlyByteBuf buf) {
        return new EnvironmentContextNaturalLanguageSetMessage(
                buf.readUUID(), buf.readVarLong(), buf.readBoolean());
    }

    /**
     * 服务端：保存总开关（纵深防御，不信任客户端）。
     * <p>
     * 与 32 项设置共用同一个浮层会话与同一套限流；管理员允许时保存，不允许时只回快照、<b>不</b>改存档——
     * 玩家原来的 true 必须保留，功能恢复后继续生效。除被限流直接丢弃外，一律回完整环境上下文快照
     * （布尔值、32 项模式与管理员状态作为同一快照应用，不部分更新）。
     */
    public static void handle(EnvironmentContextNaturalLanguageSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null || msg.sessionId == null
                    || !SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                return;
            }
            if (Config.PLAYER_OPTION_ENABLED.get()) {
                PlayerSettingsStore.setEnvironmentContextNaturalLanguageEnabled(
                        serverPlayer.server, serverPlayer.getUUID(), msg.enabled);
            }
            SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    EnvironmentContextConfigResponseMessage.snapshot(
                            serverPlayer.server, serverPlayer.getUUID(), msg.sessionId, msg.seq));
        });
        ctx.get().setPacketHandled(true);
    }
}
