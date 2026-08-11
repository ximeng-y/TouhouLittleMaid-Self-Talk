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
        PayloadRegistrar registrar = event.registrar(MaidSelfTalkMod.MODID).versioned("1");
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
            if (player instanceof ServerPlayer serverPlayer) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean globalEnabled = serverPlayer.getData(SelfTalkAttachments.SELF_TALK_ENABLED);
                boolean maidEnabled = globalEnabled
                        && !serverPlayer.getData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES)
                        .containsKey(payload.maidUuid().toString());
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
                    // 名单只存关闭项：关闭时 put false，重新开启时 remove，避免名单膨胀
                    String key = payload.maidUuid().get().toString();
                    var overrides = serverPlayer.getData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES);
                    if (payload.enabled()) {
                        overrides.remove(key);
                    } else {
                        overrides.put(key, false);
                    }
                }
            }
        });
    }

    /** 客户端：收到设置响应 */
    private static void handleConfigResponse(SelfTalkConfigResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SelfTalkPlayerSettingsClient.onConfigResponse(payload));
    }
}
