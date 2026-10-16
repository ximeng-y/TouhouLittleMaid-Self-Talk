package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

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

    /** 客户端断开连接时清空待确认记录：残留的旧连接请求不可能再回包，防止锁死重连后的草稿页 */
    static {
        NeoForge.EVENT_BUS.addListener(SelfTalkPlayerSettingsClient::onClientLoggingOut);
    }

    private static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PENDING_PERSONA_SAVES.clear();
    }

    /** 客户端级待确认人设保存：requestId → {实体 id, 女仆 UUID}。
     *  页面关闭后的迟到回包仍能据此定位目标女仆并把服务端确认值写回客户端宿主；
     *  条目随回包到达移除（服务端对可解码请求一律回包，仅异常断连会残留，容量上限兜底）。
     *  刻意不保存本地提交快照：成功时以服务端确认值为准，回包本身就是权威来源。 */
    private static final Map<UUID, PendingPersonaSave> PENDING_PERSONA_SAVES = new HashMap<>();
    /** 待确认条目容量上限：防异常断连下无限残留 */
    private static final int MAX_PENDING_PERSONA_SAVES = 64;

    private record PendingPersonaSave(int entityId, UUID maidUuid) {
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

    /** 人设保存提交时登记：请求发出即登记（实体 id + 女仆 UUID），回包到达后据此独立确认客户端宿主 */
    public static void registerPersonaSave(UUID requestId, EntityMaid maid) {
        if (requestId == null || maid == null) {
            return;
        }
        if (PENDING_PERSONA_SAVES.size() >= MAX_PENDING_PERSONA_SAVES) {
            // 容量兜底：整体清空（HashMap 顺序不稳定，仅防止无限残留，不影响正确性）
            PENDING_PERSONA_SAVES.clear();
        }
        PENDING_PERSONA_SAVES.put(requestId, new PendingPersonaSave(maid.getId(), maid.getUUID()));
    }

    /** 同一女仆是否仍有未确认的人设保存：跨页面门禁，防止旧基线在确认前被提交覆盖新值 */
    public static boolean hasPendingSaveFor(int entityId) {
        for (PendingPersonaSave pending : PENDING_PERSONA_SAVES.values()) {
            if (pending.entityId() == entityId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收到服务端人设保存结果：先独立确认客户端宿主，再通知当前人设页。
     * <p>
     * 只处理<b>本客户端登记过</b>的 requestId（未登记者一律不处理，杜绝迟到/伪造回包改写本地状态）。
     * 宿主更新不依赖页面存活：回包到达即按登记条目定位目标女仆——实体 id 与 UUID <b>双条件</b>同时匹配
     * （实体 id 可能被复用，只按 id 找会改到别的女仆身上），再把<b>服务端确认值</b>写回客户端宿主；
     * 页面关闭后重开人设页也能读到最新值。
     * <p>
     * 「请求已结束」与「成功并入」分开：无论成功失败，都要让等待它的人设页结束
     * 跨页门禁（失败不改内容/基线，只提示错误）；成功且目标身份匹配时才并入确认值。
     * 原页仍按自身 requestId 走完整响应处理，旧回包不会落到任意新页面上。
     */
    public static void onPersonaSaveResponse(MaidPersonaSaveResponsePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        PendingPersonaSave pending = PENDING_PERSONA_SAVES.remove(payload.requestId());
        if (pending != null && payload.success() && mc.level != null
                && mc.level.getEntity(pending.entityId()) instanceof EntityMaid maid
                && maid.getUUID().equals(pending.maidUuid())) {
            // 成功且身份匹配：以服务端确认值为准独立更新客户端宿主（不依赖页面存活）
            MaidPersonaHost.replace(maid, payload.confirmed());
            // 当前打开的同一女仆人设页并入确认值并解锁门禁
            if (mc.screen instanceof MaidPersonaEditScreen screen) {
                screen.onHostConfirmed(pending.entityId(), payload.confirmed());
            }
        }
        // 无论成功失败：通知当前打开的人设页「该请求已结束」，
        // 页面内按自身 requestId 匹配，不匹配的只解除跨页门禁
        if (mc.screen instanceof MaidPersonaEditScreen screen) {
            screen.onPendingSaveFinished(payload);
        }
    }
}
