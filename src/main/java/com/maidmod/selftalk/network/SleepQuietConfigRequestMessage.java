package com.maidmod.selftalk.network;

import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：客户端请求当前玩家「睡觉时安静」设置。
 * <p>
 * 该设置不受管理员 Config.PLAYER_OPTION_ENABLED 控制（响应体无 admin 字段），
 * 服务端处理时也不检查该配置。
 *
 * @param maidUuid 打开设置界面的女仆 UUID，服务端据此返回该女仆的单只有效值
 */
public class SleepQuietConfigRequestMessage {

    private final UUID maidUuid;

    public SleepQuietConfigRequestMessage(UUID maidUuid) {
        this.maidUuid = maidUuid;
    }

    public static void encode(SleepQuietConfigRequestMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidUuid);
    }

    public static SleepQuietConfigRequestMessage decode(FriendlyByteBuf buf) {
        return new SleepQuietConfigRequestMessage(buf.readUUID());
    }

    public static void handle(SleepQuietConfigRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null && msg.maidUuid != null
                    && SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                boolean globalEnabled = PlayerSettingsStore.isSleepQuietGlobal(serverPlayer.server, serverPlayer.getUUID());
                boolean maidEnabled = globalEnabled || PlayerSettingsStore.isSleepQuietMaid(
                        serverPlayer.server, serverPlayer.getUUID(), msg.maidUuid);
                SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new SleepQuietConfigResponseMessage(globalEnabled, maidEnabled));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
