package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.PlayerSettingsStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * C2S：客户端请求当前玩家自话设置（含管理员是否允许玩家配置）。
 */
public class SelfTalkConfigRequestMessage {

    public SelfTalkConfigRequestMessage() {
    }

    public static void encode(SelfTalkConfigRequestMessage msg, FriendlyByteBuf buf) {
    }

    public static SelfTalkConfigRequestMessage decode(FriendlyByteBuf buf) {
        return new SelfTalkConfigRequestMessage();
    }

    public static void handle(SelfTalkConfigRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean selfTalkEnabled = PlayerSettingsStorage.isEnabled(serverPlayer);
                SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new SelfTalkConfigResponseMessage(adminEnabled, selfTalkEnabled));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
