package com.maidmod.selftalk.client;

import com.maidmod.selftalk.network.CustomPromptConfigResponsePayload;
import com.maidmod.selftalk.network.InterChatConfigResponsePayload;
import com.maidmod.selftalk.network.SelfTalkConfigResponsePayload;
import com.maidmod.selftalk.network.SleepQuietConfigResponsePayload;
import com.maidmod.selftalk.network.ToolConfigResponsePayload;
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

    /** 收到服务端自话设置响应后，刷新当前打开的设置界面 */
    public static void onConfigResponse(SelfTalkConfigResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SelfTalkPlayerSettingsScreen screen) {
            screen.applyResponse(payload.adminEnabled(), payload.globalEnabled(), payload.maidEnabled());
        }
    }

    /** 收到服务端互聊设置响应后，刷新界面 */
    public static void onInterChatConfigResponse(InterChatConfigResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SelfTalkPlayerSettingsScreen screen) {
            screen.applyInterChatResponse(payload.adminEnabled(), payload.globalEnabled(), payload.maidEnabled());
        }
    }

    /** 收到服务端「睡觉时安静」设置响应后，刷新界面 */
    public static void onSleepQuietConfigResponse(SleepQuietConfigResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SelfTalkPlayerSettingsScreen screen) {
            screen.applySleepQuietResponse(payload.globalEnabled(), payload.maidEnabled());
        }
    }

    /** 收到服务端自定义 Prompt 设置响应后，刷新界面 */
    public static void onCustomPromptConfigResponse(CustomPromptConfigResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SelfTalkPlayerSettingsScreen screen) {
            screen.applyCustomPromptResponse(payload.adminEnabled(), payload.globalPrompt(),
                    payload.maidPrompt(), payload.overrideEnabled());
        }
    }

    /** 收到服务端 Tool 调用设置响应后，刷新界面 */
    public static void onToolConfigResponse(ToolConfigResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SelfTalkPlayerSettingsScreen screen) {
            screen.applyToolResponse(payload.adminEnabled(), payload.toolAdminEnabled(),
                    payload.globalEnabled(), payload.maidEnabled());
        }
    }
}
