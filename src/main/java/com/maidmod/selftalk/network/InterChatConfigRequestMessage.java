package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.PlayerSettingsStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：客户端请求当前玩家互聊设置（含管理员是否允许玩家配置）。
 *
 * @param maidUuid 打开设置界面的女仆 UUID，服务端据此返回该女仆的单只有效值
 */
public class InterChatConfigRequestMessage {

    private final UUID maidUuid;

    public InterChatConfigRequestMessage(UUID maidUuid) {
        this.maidUuid = maidUuid;
    }

    public static void encode(InterChatConfigRequestMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidUuid);
    }

    public static InterChatConfigRequestMessage decode(FriendlyByteBuf buf) {
        return new InterChatConfigRequestMessage(buf.readUUID());
    }

    public static void handle(InterChatConfigRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null && msg.maidUuid != null
                    && SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean globalEnabled = PlayerSettingsStorage.isInterChatEnabled(serverPlayer);
                boolean maidEnabled = globalEnabled
                        && !PlayerSettingsStorage.isInterChatMaidDisabled(serverPlayer, msg.maidUuid);
                SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new InterChatConfigResponseMessage(adminEnabled, globalEnabled, maidEnabled));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
