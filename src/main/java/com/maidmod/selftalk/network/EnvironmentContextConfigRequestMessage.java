package com.maidmod.selftalk.network;

import com.maidmod.selftalk.EnvironmentContextOption;
import com.maidmod.selftalk.EnvironmentContextSettings;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：请求环境上下文设置的全量权威快照。
 * <p>
 * 包内<b>不携带</b> player UUID 或 maid UUID：这是玩家自己的全局偏好，服务端一律用连接上的
 * {@link ServerPlayer} 身份读写，不需要当前女仆加载、也不做单只归属检查。
 *
 * @param sessionId 本次浮层会话 id（关闭重开即换新，用于丢弃迟到回包）
 * @param seq       同一会话内递增的请求序号
 */
public class EnvironmentContextConfigRequestMessage {

    private final UUID sessionId;
    private final long seq;

    public EnvironmentContextConfigRequestMessage(UUID sessionId, long seq) {
        this.sessionId = sessionId;
        this.seq = seq;
    }

    public static void encode(EnvironmentContextConfigRequestMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sessionId);
        buf.writeVarLong(msg.seq);
    }

    public static EnvironmentContextConfigRequestMessage decode(FriendlyByteBuf buf) {
        return new EnvironmentContextConfigRequestMessage(buf.readUUID(), buf.readVarLong());
    }

    /**
     * 管理员不允许编辑时<b>照样返回完整快照</b>供查看（界面需显示已存偏好并说明为何不可改）；
     * 被限流丢弃的请求不回包，不能借「回包」绕过限流扩大流量。
     */
    public static void handle(EnvironmentContextConfigRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null || msg.sessionId == null
                    || !SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                return;
            }
            SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    EnvironmentContextConfigResponseMessage.snapshot(
                            serverPlayer.server, serverPlayer.getUUID(), msg.sessionId, msg.seq));
        });
        ctx.get().setPacketHandled(true);
    }
}
