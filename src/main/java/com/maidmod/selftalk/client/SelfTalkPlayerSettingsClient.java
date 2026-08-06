package com.maidmod.selftalk.client;

import com.maidmod.selftalk.network.SelfTalkConfigResponsePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端辅助入口：网络层回调（仅客户端加载）。
 */
@OnlyIn(Dist.CLIENT)
public final class SelfTalkPlayerSettingsClient {

    private SelfTalkPlayerSettingsClient() {
    }

    /** 收到服务端设置响应后，刷新当前打开的设置界面 */
    public static void onConfigResponse(SelfTalkConfigResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SelfTalkPlayerSettingsScreen screen) {
            screen.applyResponse(payload.adminEnabled(), payload.selfTalkEnabled());
        }
    }
}
