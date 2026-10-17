package com.maidmod.selftalk.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：请求预制复用设置的权威快照。
 * <p>
 * 包内<b>不携带</b> player UUID：玩家身份一律取连接上的 {@link ServerPlayer}。
 * {@code maidUuid} 为当前打开设置界面的女仆（可能为 null——仅查看全局默认时）；
 * 非空时服务端按玩家 UUID 读该女仆的三态，但<b>不做</b>归属校验（读路径无副作用，
 * 非本人女仆在存档中本就无条目，读到「跟随默认」）。
 *
 * @param sessionId 本次浮层会话 id（关闭重开即换新，用于丢弃迟到回包）
 * @param seq       同一会话内递增的请求序号
 * @param maidUuid  当前女仆 UUID，可为 null
 */
public class ReuseConfigRequestMessage {

    private final UUID sessionId;
    private final long seq;
    private final UUID maidUuid;

    public ReuseConfigRequestMessage(UUID sessionId, long seq, UUID maidUuid) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.maidUuid = maidUuid;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public long seq() {
        return seq;
    }

    public UUID maidUuid() {
        return maidUuid;
    }

    /** 可空 UUID 写入（1.20.1 的 FriendlyByteBuf 无内建可空编解码，用布尔前缀） */
    static void writeNullableUuid(FriendlyByteBuf buf, UUID uuid) {
        buf.writeBoolean(uuid != null);
        if (uuid != null) {
            buf.writeUUID(uuid);
        }
    }

    static UUID readNullableUuid(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }

    public static void encode(ReuseConfigRequestMessage msg, FriendlyByteBuf buf) {
        writeNullableUuid(buf, msg.sessionId);
        buf.writeVarLong(msg.seq);
        writeNullableUuid(buf, msg.maidUuid);
    }

    public static ReuseConfigRequestMessage decode(FriendlyByteBuf buf) {
        return new ReuseConfigRequestMessage(readNullableUuid(buf), buf.readVarLong(), readNullableUuid(buf));
    }

    /** 服务端：回权威快照（会话/序号原样带回，客户端据此丢弃迟到回包） */
    public static void handle(ReuseConfigRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null || msg.sessionId == null
                    || !SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                return;
            }
            SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    ReuseConfigResponseMessage.snapshot(serverPlayer.server, serverPlayer.getUUID(),
                            msg.sessionId, msg.seq, msg.maidUuid));
        });
        ctx.get().setPacketHandled(true);
    }
}
