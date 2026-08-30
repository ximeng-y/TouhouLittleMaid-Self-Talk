package com.maidmod.selftalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.PlayerSettingsStore;
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
 * 玩家设置网络包注册与处理（自话 + 互聊）。
 * <p>
 * 由主类通过 {@code modEventBus.addListener(SelfTalkPackets::register)} 手动注册。
 */
public final class SelfTalkPackets {

    /** 单只关闭名单容量上限：防恶意客户端用任意 UUID 无限撑大附件与存档（每次写入还有全量拷贝放大） */
    private static final int MAX_MAID_OVERRIDES = 256;
    /** 每玩家每秒最多处理的设置包数（正常 UI 操作远低于此，仅防包风暴） */
    private static final int MAX_CONFIG_PACKETS_PER_SECOND = 20;
    /** 玩家 UUID -> [上次处理的秒, 该秒内处理数]（仅服务端主线程访问；玩家登出时随 removeRateEntry 清理） */
    private static final Map<UUID, long[]> PACKET_RATE = new HashMap<>();

    private SelfTalkPackets() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // v4：新增「睡觉时安静」三个 payload；NeoForge 按版本串强制协商，
        // 不匹配版本互相拒绝进服，升级需全服同步
        PayloadRegistrar registrar = event.registrar(MaidSelfTalkMod.MODID).versioned("4");
        registrar.playToServer(SelfTalkConfigRequestPayload.TYPE, SelfTalkConfigRequestPayload.STREAM_CODEC,
                SelfTalkPackets::handleConfigRequest);
        registrar.playToServer(SelfTalkConfigSetPayload.TYPE, SelfTalkConfigSetPayload.STREAM_CODEC,
                SelfTalkPackets::handleConfigSet);
        registrar.playToClient(SelfTalkConfigResponsePayload.TYPE, SelfTalkConfigResponsePayload.STREAM_CODEC,
                SelfTalkPackets::handleConfigResponse);
        registrar.playToServer(InterChatConfigRequestPayload.TYPE, InterChatConfigRequestPayload.STREAM_CODEC,
                SelfTalkPackets::handleInterChatRequest);
        registrar.playToServer(InterChatConfigSetPayload.TYPE, InterChatConfigSetPayload.STREAM_CODEC,
                SelfTalkPackets::handleInterChatSet);
        registrar.playToClient(InterChatConfigResponsePayload.TYPE, InterChatConfigResponsePayload.STREAM_CODEC,
                SelfTalkPackets::handleInterChatResponse);
        registrar.playToServer(SleepQuietConfigRequestPayload.TYPE, SleepQuietConfigRequestPayload.STREAM_CODEC,
                SelfTalkPackets::handleSleepQuietRequest);
        registrar.playToServer(SleepQuietConfigSetPayload.TYPE, SleepQuietConfigSetPayload.STREAM_CODEC,
                SelfTalkPackets::handleSleepQuietSet);
        registrar.playToClient(SleepQuietConfigResponsePayload.TYPE, SleepQuietConfigResponsePayload.STREAM_CODEC,
                SelfTalkPackets::handleSleepQuietResponse);
    }

    /** 服务端：响应玩家的自话设置查询（全局值 + 请求女仆的单只有效值；1.1.2 起读世界存档、离线亦可查） */
    private static void handleConfigRequest(SelfTalkConfigRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && payload.maidUuid() != null
                    && allowConfigPacket(serverPlayer.getUUID())) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean globalEnabled = PlayerSettingsStore.isSelfTalkEnabled(serverPlayer.server, serverPlayer.getUUID());
                boolean maidEnabled = globalEnabled && !PlayerSettingsStore.isSelfTalkMaidDisabled(
                        serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid());
                context.reply(new SelfTalkConfigResponsePayload(adminEnabled, globalEnabled, maidEnabled));
            }
        });
    }

    /** 服务端：保存玩家自话设置（maidUuid 为空 → 全局开关；非空 → 单只关闭名单） */
    private static void handleConfigSet(SelfTalkConfigSetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && allowConfigPacket(serverPlayer.getUUID())) {
                if (payload.maidUuid().isEmpty()) {
                    PlayerSettingsStore.setSelfTalkEnabled(serverPlayer.server, serverPlayer.getUUID(), payload.enabled());
                } else {
                    // 归属校验：仅允许主人操作自己拥有的女仆，防伪造 UUID 污染名单
                    if (!isOwnedMaid(serverPlayer, payload.maidUuid().get())) {
                        return;
                    }
                    // 名单只存关闭项：关闭时新增条目，重新开启时移除，避免名单膨胀。
                    // 容量上限：新增键时校验，防恶意客户端伪造任意 UUID 无限撑大世界存档
                    if (!payload.enabled() && !PlayerSettingsStore.isSelfTalkMaidDisabled(
                            serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid().get())
                            && PlayerSettingsStore.countMaidOverrides(serverPlayer.server,
                            SelfTalkAttachments.LEVEL_SELF_TALK_MAID_OVERRIDES, serverPlayer.getUUID()) >= MAX_MAID_OVERRIDES) {
                        MaidSelfTalkMod.LOGGER.warn("Player {} self-talk maid override list full ({}), ignored",
                                serverPlayer.getUUID(), MAX_MAID_OVERRIDES);
                        return;
                    }
                    PlayerSettingsStore.setSelfTalkMaidDisabled(serverPlayer.server,
                            serverPlayer.getUUID(), payload.maidUuid().get(), !payload.enabled());
                }
            }
        });
    }

    /** 服务端：响应玩家的互聊设置查询（全局值 + 请求女仆的单只有效值；1.1.2 起读世界存档、离线亦可查） */
    private static void handleInterChatRequest(InterChatConfigRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && payload.maidUuid() != null
                    && allowConfigPacket(serverPlayer.getUUID())) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean globalEnabled = PlayerSettingsStore.isInterChatEnabled(serverPlayer.server, serverPlayer.getUUID());
                boolean maidEnabled = globalEnabled && !PlayerSettingsStore.isInterChatMaidDisabled(
                        serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid());
                context.reply(new InterChatConfigResponsePayload(adminEnabled, globalEnabled, maidEnabled));
            }
        });
    }

    /** 服务端：保存玩家互聊设置（maidUuid 为空 → 全局开关；非空 → 单只关闭名单） */
    private static void handleInterChatSet(InterChatConfigSetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && allowConfigPacket(serverPlayer.getUUID())) {
                if (payload.maidUuid().isEmpty()) {
                    PlayerSettingsStore.setInterChatEnabled(serverPlayer.server, serverPlayer.getUUID(), payload.enabled());
                } else {
                    // 归属校验：仅允许主人操作自己拥有的女仆，防伪造 UUID 污染名单
                    if (!isOwnedMaid(serverPlayer, payload.maidUuid().get())) {
                        return;
                    }
                    // 名单只存关闭项：关闭时新增条目，重新开启时移除，避免名单膨胀
                    if (!payload.enabled() && !PlayerSettingsStore.isInterChatMaidDisabled(
                            serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid().get())
                            && PlayerSettingsStore.countMaidOverrides(serverPlayer.server,
                            SelfTalkAttachments.LEVEL_INTER_CHAT_MAID_OVERRIDES, serverPlayer.getUUID()) >= MAX_MAID_OVERRIDES) {
                        MaidSelfTalkMod.LOGGER.warn("Player {} inter-chat maid override list full ({}), ignored",
                                serverPlayer.getUUID(), MAX_MAID_OVERRIDES);
                        return;
                    }
                    PlayerSettingsStore.setInterChatMaidDisabled(serverPlayer.server,
                            serverPlayer.getUUID(), payload.maidUuid().get(), !payload.enabled());
                }
            }
        });
    }

    /**
     * 服务端：响应玩家的「睡觉时安静」设置查询。
     * 该设置不受管理员 Config.PLAYER_OPTION_ENABLED 控制（response 无 admin 字段）。
     * maidEnabled = 全局安静 或 单只名单含该女仆（全局 false 时名单才生效）。
     */
    private static void handleSleepQuietRequest(SleepQuietConfigRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && payload.maidUuid() != null
                    && allowConfigPacket(serverPlayer.getUUID())) {
                boolean globalEnabled = PlayerSettingsStore.isSleepQuietGlobal(serverPlayer.server, serverPlayer.getUUID());
                boolean maidEnabled = globalEnabled || PlayerSettingsStore.isSleepQuietMaid(
                        serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid());
                context.reply(new SleepQuietConfigResponsePayload(globalEnabled, maidEnabled));
            }
        });
    }

    /** 服务端：保存玩家「睡觉时安静」设置（maidUuid 为空 → 全局开关；非空 → 单只安静名单，仅全局 false 时生效） */
    private static void handleSleepQuietSet(SleepQuietConfigSetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && allowConfigPacket(serverPlayer.getUUID())) {
                if (payload.maidUuid().isEmpty()) {
                    PlayerSettingsStore.setSleepQuietGlobal(serverPlayer.server, serverPlayer.getUUID(), payload.enabled());
                } else {
                    // 归属校验：仅允许主人操作自己拥有的女仆，防伪造 UUID 污染名单
                    if (!isOwnedMaid(serverPlayer, payload.maidUuid().get())) {
                        return;
                    }
                    // 新增安静条目时校验容量上限（防恶意客户端伪造任意 UUID 无限撑大世界存档）
                    if (payload.enabled() && !PlayerSettingsStore.isSleepQuietMaid(
                            serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid().get())
                            && PlayerSettingsStore.countMaidOverrides(serverPlayer.server,
                            SelfTalkAttachments.LEVEL_SLEEP_QUIET_MAID_OVERRIDES, serverPlayer.getUUID()) >= MAX_MAID_OVERRIDES) {
                        MaidSelfTalkMod.LOGGER.warn("Player {} sleep-quiet maid override list full ({}), ignored",
                                serverPlayer.getUUID(), MAX_MAID_OVERRIDES);
                        return;
                    }
                    PlayerSettingsStore.setSleepQuietMaid(serverPlayer.server,
                            serverPlayer.getUUID(), payload.maidUuid().get(), payload.enabled());
                }
            }
        });
    }

    /** 校验女仆是否存在且属于该玩家（防伪造 UUID 写入他人女仆或不存在实体的条目） */
    private static boolean isOwnedMaid(ServerPlayer player, UUID maidUuid) {
        return player.serverLevel().getEntity(maidUuid) instanceof EntityMaid maid
                && player.getUUID().equals(maid.getOwnerUUID());
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
            MaidSelfTalkMod.LOGGER.warn("Player {} exceeded config packet rate limit", playerUuid);
            return false;
        }
        entry[1]++;
        return true;
    }

    /** 玩家登出时清理限流条目，防长期多人服务端内存缓慢增长 */
    public static void removeRateEntry(UUID playerUuid) {
        PACKET_RATE.remove(playerUuid);
    }

    /** 客户端：收到自话设置响应 */
    private static void handleConfigResponse(SelfTalkConfigResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SelfTalkPlayerSettingsClient.onConfigResponse(payload));
    }

    /** 客户端：收到互聊设置响应 */
    private static void handleInterChatResponse(InterChatConfigResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SelfTalkPlayerSettingsClient.onInterChatConfigResponse(payload));
    }

    /** 客户端：收到「睡觉时安静」设置响应 */
    private static void handleSleepQuietResponse(SleepQuietConfigResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SelfTalkPlayerSettingsClient.onSleepQuietConfigResponse(payload));
    }
}
