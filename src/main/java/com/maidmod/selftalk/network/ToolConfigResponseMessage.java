package com.maidmod.selftalk.network;

import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：服务端返回玩家 Tool 调用设置。
 *
 * @param adminEnabled     管理员是否允许玩家配置（PLAYER_OPTION_ENABLED，false 时界面置灰）
 * @param toolAdminEnabled 管理员是否开启 Tool 功能（TOOL_CALL_ENABLED）。
 *                         与 adminEnabled 分开返回，界面提示语才能区分「管理员关了玩家配置」和「管理员没开 Tool 功能」
 * @param globalEnabled    玩家 Tool 全局开关（缺省 false）
 * @param maidEnabled      请求的女仆单只有效值（全局 && 单只名单不含关闭项）
 */
public class ToolConfigResponseMessage {

    private final boolean adminEnabled;
    private final boolean toolAdminEnabled;
    private final boolean globalEnabled;
    private final boolean maidEnabled;

    public ToolConfigResponseMessage(boolean adminEnabled, boolean toolAdminEnabled,
                                     boolean globalEnabled, boolean maidEnabled) {
        this.adminEnabled = adminEnabled;
        this.toolAdminEnabled = toolAdminEnabled;
        this.globalEnabled = globalEnabled;
        this.maidEnabled = maidEnabled;
    }

    public boolean isAdminEnabled() {
        return adminEnabled;
    }

    public boolean isToolAdminEnabled() {
        return toolAdminEnabled;
    }

    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public boolean isMaidEnabled() {
        return maidEnabled;
    }

    public static void encode(ToolConfigResponseMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.adminEnabled);
        buf.writeBoolean(msg.toolAdminEnabled);
        buf.writeBoolean(msg.globalEnabled);
        buf.writeBoolean(msg.maidEnabled);
    }

    public static ToolConfigResponseMessage decode(FriendlyByteBuf buf) {
        return new ToolConfigResponseMessage(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(ToolConfigResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SelfTalkPlayerSettingsClient.onToolConfigResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
