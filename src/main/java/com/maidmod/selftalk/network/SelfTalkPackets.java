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
import java.util.UUID;

/**
 * 玩家自话设置网络包注册与处理。
 * <p>
 * 由主类通过 {@code modEventBus.addListener(SelfTalkPackets::register)} 手动注册。
 */
public final class SelfTalkPackets {

    /** 单只关闭名单容量上限：防恶意客户端用任意 UUID 无限撑大附件与存档（每次写入还有全量拷贝放大） */
    private static final int MAX_MAID_OVERRIDES = 128;
    /** 每玩家每秒最多处理的设置包数（正常 UI 操作远低于此，仅防包风暴） */
    private static final int MAX_CONFIG_PACKETS_PER_SECOND = 20;
    /** 玩家 UUID -> [上次处理的秒, 该秒内处理数]（仅服务端主线程访问；条目极小，随玩家长期积累可忽略） */
    private static final Map<UUID, long[]> PACKET_RATE = new HashMap<>();

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
            if (player instanceof ServerPlayer serverPlayer && payload.maidUuid() != null
                    && allowConfigPacket(serverPlayer.getUUID())) {
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
            if (player instanceof ServerPlayer serverPlayer && allowConfigPacket(serverPlayer.getUUID())) {
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
                        // 容量上限：新增键时校验，防恶意客户端伪造任意 UUID 无限撑大名单
                        if (!overrides.containsKey(key) && overrides.size() >= MAX_MAID_OVERRIDES) {
                            MaidSelfTalkMod.LOGGER.warn("Player {} self-talk maid override list full ({}), ignored",
                                    serverPlayer.getUUID(), MAX_MAID_OVERRIDES);
                            return;
                        }
                        overrides.put(key, false);
                    }
                    serverPlayer.setData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES, overrides);
                }
            }
        });
    }

    /** 每玩家每秒限流：防恶意客户端包风暴（正常设置界面操作远低于该频率） */
    private static boolean allowConfigPacket(UUID playerUuid) {
        long second = System.currentTimeMillis() / 1000;
        long[] entry = PACKET_RATE.get(playerUuid);
        if (entry == null) {
            PACKET_RATE.put(playerUuid, new long[]{second, 1});
            return true;
        }
        if (entry[0] != second) {
            entry[0] = second;
            entry[1] = 1;
            return true;
        }
        if (entry[1] >= MAX_CONFIG_PACKETS_PER_SECOND) {
            MaidSelfTalkMod.LOGGER.warn("Player {} exceeded self-talk config packet rate limit", playerUuid);
            return false;
        }
        entry[1]++;
        return true;
    }

    /** 客户端：收到设置响应 */
    private static void handleConfigResponse(SelfTalkConfigResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SelfTalkPlayerSettingsClient.onConfigResponse(payload));
    }
}
