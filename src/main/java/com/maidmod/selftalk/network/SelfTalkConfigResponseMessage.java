package com.maidmod.selftalk.network;

import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：服务端返回玩家自话设置。
 *
 * @param adminEnabled  管理员是否允许玩家配置（false 时客户端 UI 置灰）
 * @param globalEnabled 玩家全局开关（自己的所有女仆）
 * @param maidEnabled   请求的女仆单只有效值（全局 && 单只名单）
 */
public class SelfTalkConfigResponseMessage {

    private final boolean adminEnabled;
    private final boolean globalEnabled;
    private final boolean maidEnabled;

    public SelfTalkConfigResponseMessage(boolean adminEnabled, boolean globalEnabled, boolean maidEnabled) {
        this.adminEnabled = adminEnabled;
        this.globalEnabled = globalEnabled;
        this.maidEnabled = maidEnabled;
    }

    public boolean isAdminEnabled() {
        return adminEnabled;
    }

    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public boolean isMaidEnabled() {
        return maidEnabled;
    }

    public static void encode(SelfTalkConfigResponseMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.adminEnabled);
        buf.writeBoolean(msg.globalEnabled);
        buf.writeBoolean(msg.maidEnabled);
    }

    public static SelfTalkConfigResponseMessage decode(FriendlyByteBuf buf) {
        return new SelfTalkConfigResponseMessage(buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(SelfTalkConfigResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SelfTalkPlayerSettingsClient.onConfigResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
