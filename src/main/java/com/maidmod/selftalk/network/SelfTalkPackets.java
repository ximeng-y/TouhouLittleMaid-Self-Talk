package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.SelfTalkAttachments;
import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.HashMap;
import java.util.Map;

/**
 * 玩家自话设置网络包注册与处理。
 * <p>
 * 由主类通过 {@code modEventBus.addListener(SelfTalkPackets::register)} 手动注册。
 */
public final class SelfTalkPackets {

    private SelfTalkPackets() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // v2：玩家设置两级化后三个 payload 布局均变更，升版让不匹配版本在协商期被拒绝
        PayloadRegistrar registrar = event.registrar(MaidSelfTalkMod.MODID).versioned("2");
        registrar.playToServer(SelfTalkConfigRequestPayload.TYPE, SelfTalkConfigRequestPayload.STREAM_CODEC,
                SelfTalkPackets::handleConfigRequest);
        registrar.playToServer(SelfTalkConfigSetPayload.TYPE, SelfTalkConfigSetPayload.STREAM_CODEC,
                SelfTalkPackets::handleConfigSet);
        registrar.playToClient(SelfTalkConfigResponsePayload.TYPE, SelfTalkConfigResponsePayload.STREAM_CODEC,
                SelfTalkPackets::handleConfigResponse);
    }

    /** 服务端：响应玩家的设置查询（全局值 + 请求女仆的单只有效值） */
    private static void handleConfigRequest(SelfTalkConfigRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && payload.maidUuid() != null) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean globalEnabled = serverPlayer.getData(SelfTalkAttachments.SELF_TALK_ENABLED);
                boolean maidEnabled = globalEnabled && serverPlayer
                        .getExistingData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES)
                        .map(overrides -> !overrides.containsKey(payload.maidUuid().toString()))
                        .orElse(true);
                context.reply(new SelfTalkConfigResponsePayload(adminEnabled, globalEnabled, maidEnabled));
            }
        });
    }

    /** 服务端：保存玩家设置（maidUuid 为空 → 全局开关；非空 → 单只关闭名单） */
    private static void handleConfigSet(SelfTalkConfigSetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                if (payload.maidUuid().isEmpty()) {
                    serverPlayer.setData(SelfTalkAttachments.SELF_TALK_ENABLED, payload.enabled());
                } else {
                    // 名单只存关闭项：关闭时 put false，重新开启时 remove，避免名单膨胀。
                    // 拷贝后 setData 回写，而非原地修改（防未来加 networkSerialize 时漏同步）
                    Map<String, Boolean> overrides = new HashMap<>(
                            serverPlayer.getData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES));
                    String key = payload.maidUuid().get().toString();
                    if (payload.enabled()) {
                        overrides.remove(key);
                    } else {
                        overrides.put(key, false);
                    }
                    serverPlayer.setData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES, overrides);
                }
            }
        });
    }

    /** 客户端：收到设置响应 */
    private static void handleConfigResponse(SelfTalkConfigResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SelfTalkPlayerSettingsClient.onConfigResponse(payload));
    }
}
