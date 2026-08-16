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
 * C2S：客户端请求当前玩家自话设置（含管理员是否允许玩家配置）。
 *
 * @param maidUuid 打开设置界面的女仆 UUID，服务端据此返回该女仆的单只有效值
 */
public class SelfTalkConfigRequestMessage {

    private final UUID maidUuid;

    public SelfTalkConfigRequestMessage(UUID maidUuid) {
        this.maidUuid = maidUuid;
    }

    public static void encode(SelfTalkConfigRequestMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidUuid);
    }

    public static SelfTalkConfigRequestMessage decode(FriendlyByteBuf buf) {
        return new SelfTalkConfigRequestMessage(buf.readUUID());
    }

    public static void handle(SelfTalkConfigRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null && msg.maidUuid != null
                    && SelfTalkConfigSetMessage.allowConfigPacket(serverPlayer.getUUID())) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean globalEnabled = PlayerSettingsStorage.isEnabled(serverPlayer);
                boolean maidEnabled = globalEnabled
                        && !PlayerSettingsStorage.isMaidDisabled(serverPlayer, msg.maidUuid);
                SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new SelfTalkConfigResponseMessage(adminEnabled, globalEnabled, maidEnabled));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
