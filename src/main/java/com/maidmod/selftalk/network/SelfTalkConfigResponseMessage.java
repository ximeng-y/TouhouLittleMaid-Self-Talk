package com.maidmod.selftalk.network;

import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：服务端返回玩家自话设置。
 */
public class SelfTalkConfigResponseMessage {

    private final boolean adminEnabled;
    private final boolean selfTalkEnabled;

    public SelfTalkConfigResponseMessage(boolean adminEnabled, boolean selfTalkEnabled) {
        this.adminEnabled = adminEnabled;
        this.selfTalkEnabled = selfTalkEnabled;
    }

    public boolean isAdminEnabled() {
        return adminEnabled;
    }

    public boolean isSelfTalkEnabled() {
        return selfTalkEnabled;
    }

    public static void encode(SelfTalkConfigResponseMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.adminEnabled);
        buf.writeBoolean(msg.selfTalkEnabled);
    }

    public static SelfTalkConfigResponseMessage decode(FriendlyByteBuf buf) {
        return new SelfTalkConfigResponseMessage(buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(SelfTalkConfigResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SelfTalkPlayerSettingsClient.onConfigResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
