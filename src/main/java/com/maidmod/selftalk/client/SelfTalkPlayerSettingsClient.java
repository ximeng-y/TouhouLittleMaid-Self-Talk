package com.maidmod.selftalk.client;

import com.maidmod.selftalk.network.SelfTalkConfigResponseMessage;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 客户端辅助入口：网络层回调（仅客户端加载）。
 */
@OnlyIn(Dist.CLIENT)
public final class SelfTalkPlayerSettingsClient {

    private SelfTalkPlayerSettingsClient() {
    }

    /** 收到服务端设置响应后，刷新当前打开的设置界面 */
    public static void onConfigResponse(SelfTalkConfigResponseMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SelfTalkPlayerSettingsScreen screen) {
            screen.applyResponse(msg.isAdminEnabled(), msg.isSelfTalkEnabled());
        }
    }
}
