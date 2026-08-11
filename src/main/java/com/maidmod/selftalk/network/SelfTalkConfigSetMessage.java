package com.maidmod.selftalk.network;

import com.maidmod.selftalk.PlayerSettingsStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：客户端提交玩家自话设置。
 */
public class SelfTalkConfigSetMessage {

    private final boolean selfTalkEnabled;

    public SelfTalkConfigSetMessage(boolean selfTalkEnabled) {
        this.selfTalkEnabled = selfTalkEnabled;
    }

    public static void encode(SelfTalkConfigSetMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.selfTalkEnabled);
    }

    public static SelfTalkConfigSetMessage decode(FriendlyByteBuf buf) {
        return new SelfTalkConfigSetMessage(buf.readBoolean());
    }

    public static void handle(SelfTalkConfigSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null) {
                PlayerSettingsStorage.setEnabled(serverPlayer, msg.selfTalkEnabled);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
