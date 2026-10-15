package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.network.CustomPromptConfigResponsePayload;
import com.maidmod.selftalk.network.EnvironmentContextConfigResponsePayload;
import com.maidmod.selftalk.network.InterChatConfigResponsePayload;
import com.maidmod.selftalk.network.MaidPersonaSaveResponsePayload;
import com.maidmod.selftalk.network.SelfTalkConfigResponsePayload;
import com.maidmod.selftalk.network.SleepQuietConfigResponsePayload;
import com.maidmod.selftalk.network.ToolConfigResponsePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端辅助入口：网络层回调（仅客户端加载）。
 */
@OnlyIn(Dist.CLIENT)
public final class SelfTalkPlayerSettingsClient {

    private SelfTalkPlayerSettingsClient() {
    }

    /** 客户端级待确认草稿保存：requestId → {实体 id, 提交快照}。
     *  页面关闭后的迟到回包仍能据此更新客户端宿主，重开草稿页读到的是服务端已确认值；
     *  条目随回包到达移除（服务端对可解码请求一律回包，仅异常断连会残留，容量上限兜底）。 */
    private static final Map<UUID, PendingPersonaSave> PENDING_PERSONA_SAVES = new HashMap<>();
    /** 待确认条目容量上限：防异常断连下无限残留 */
    private static final int MAX_PENDING_PERSONA_SAVES = 64;

    private record PendingPersonaSave(int entityId, MaidPersonaDraft snapshot) {
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
            screen.applyToolResponse(payload.toolAdminEnabled(),
                    payload.globalEnabled(), payload.maidEnabled());
        }
    }

    /** 收到服务端环境上下文设置响应后转交浮层（会话与序号校验在浮层内） */
    public static void onEnvironmentContextResponse(EnvironmentContextConfigResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof SelfTalkPlayerSettingsScreen screen) {
            screen.applyEnvironmentContextResponse(payload);
        }
    }

    /** 草稿保存提交时登记：请求发出即登记，回包到达后据此独立确认客户端宿主 */
    public static void registerPersonaSave(UUID requestId, EntityMaid maid, MaidPersonaDraft snapshot) {
        if (requestId == null || maid == null) {
            return;
        }
        if (PENDING_PERSONA_SAVES.size() >= MAX_PENDING_PERSONA_SAVES) {
            // 容量兜底：整体清空（HashMap 顺序不稳定，仅防止无限残留，不影响正确性）
            PENDING_PERSONA_SAVES.clear();
        }
        PENDING_PERSONA_SAVES.put(requestId, new PendingPersonaSave(maid.getId(), snapshot));
    }

    /** 同一女仆是否仍有未确认的草稿保存：跨页面门禁，防止旧基线在确认前被提交覆盖新值 */
    public static boolean hasPendingSaveFor(int entityId) {
        for (PendingPersonaSave pending : PENDING_PERSONA_SAVES.values()) {
            if (pending.entityId() == entityId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收到服务端草稿保存结果：先独立确认客户端宿主，再决定是否更新当前 UI。
     * <p>
     * 宿主更新不依赖页面存活：回包到达即按提交登记把服务端已保存值写回客户端宿主，
     * 页面关闭后重开草稿页也能读到最新值；旧 requestId 不操作新页面
     * （页面内仍按自身 pendingRequestId 匹配，迟到回包不会落到新页面上）。
     */
    public static void onPersonaSaveResponse(MaidPersonaSaveResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        PendingPersonaSave pending = PENDING_PERSONA_SAVES.remove(payload.requestId());
        if (payload.success() && pending != null && mc.level != null
                && mc.level.getEntity(pending.entityId()) instanceof EntityMaid maid) {
            // 客户端宿主更新不依赖页面存活：页面关闭后重开草稿页读到服务端已确认值
            MaidPersonaHost.replace(maid, pending.snapshot());
            // 回包前已重开的本女仆草稿页同步并入确认值（页面内判定是否有未保存改动）
            if (mc.screen instanceof MaidPersonaEditScreen screen) {
                screen.onHostConfirmed(pending.entityId(), pending.snapshot());
            }
        }
        if (mc.screen instanceof MaidPersonaEditScreen screen) {
            screen.applySaveResponse(payload);
        }
    }
}
