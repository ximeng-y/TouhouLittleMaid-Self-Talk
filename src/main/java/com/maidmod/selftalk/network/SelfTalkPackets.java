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

    /** 服务端：响应玩家的设置查询 */
    private static void handleConfigRequest(SelfTalkConfigRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean selfTalkEnabled = serverPlayer.getData(SelfTalkAttachments.SELF_TALK_ENABLED);
                context.reply(new SelfTalkConfigResponsePayload(adminEnabled, selfTalkEnabled));
            }
        });
    }

    /** 服务端：保存玩家的设置 */
    private static void handleConfigSet(SelfTalkConfigSetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.setData(SelfTalkAttachments.SELF_TALK_ENABLED, payload.selfTalkEnabled());
            }
        });
    }

    /** 客户端：收到设置响应 */
    private static void handleConfigResponse(SelfTalkConfigResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SelfTalkPlayerSettingsClient.onConfigResponse(payload));
    }
}
