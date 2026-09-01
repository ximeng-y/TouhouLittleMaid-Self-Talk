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
    /** 自定义 Prompt 服务端存储上限（字符）：界面输入框同步限长，流侧另有 600 兜底 */
    private static final int MAX_CUSTOM_PROMPT_LENGTH = 500;
    /** 每玩家每秒最多处理的设置包数（正常 UI 操作远低于此，仅防包风暴） */
    private static final int MAX_CONFIG_PACKETS_PER_SECOND = 20;
    /** 玩家 UUID -> [上次处理的秒, 该秒内处理数]（仅服务端主线程访问；玩家登出时随 removeRateEntry 清理） */
    private static final Map<UUID, long[]> PACKET_RATE = new HashMap<>();

    private SelfTalkPackets() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // v5：新增「自定义 Prompt」与「Tool 调用」共 6 个 payload；按 network-backward-compat
        // 既定决策不做向后兼容——NeoForge 按版本串强制协商，不匹配版本互相拒绝进服，升级需全服同步
        PayloadRegistrar registrar = event.registrar(MaidSelfTalkMod.MODID).versioned("5");
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
        registrar.playToServer(CustomPromptConfigRequestPayload.TYPE, CustomPromptConfigRequestPayload.STREAM_CODEC,
                SelfTalkPackets::handleCustomPromptRequest);
        registrar.playToServer(CustomPromptConfigSetPayload.TYPE, CustomPromptConfigSetPayload.STREAM_CODEC,
                SelfTalkPackets::handleCustomPromptSet);
        registrar.playToClient(CustomPromptConfigResponsePayload.TYPE, CustomPromptConfigResponsePayload.STREAM_CODEC,
                SelfTalkPackets::handleCustomPromptResponse);
        registrar.playToServer(ToolConfigRequestPayload.TYPE, ToolConfigRequestPayload.STREAM_CODEC,
                SelfTalkPackets::handleToolRequest);
        registrar.playToServer(ToolConfigSetPayload.TYPE, ToolConfigSetPayload.STREAM_CODEC,
                SelfTalkPackets::handleToolSet);
        registrar.playToClient(ToolConfigResponsePayload.TYPE, ToolConfigResponsePayload.STREAM_CODEC,
                SelfTalkPackets::handleToolResponse);
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

    /**
     * 服务端：响应玩家的自定义 Prompt 设置查询（全局段 + 请求女仆的单只段 + 覆盖开关）。
     * 返回的是存档原文（未清洗）；清洗只在构造请求注入时做，界面须原样回显玩家输入。
     */
    private static void handleCustomPromptRequest(CustomPromptConfigRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && payload.maidUuid() != null
                    && allowConfigPacket(serverPlayer.getUUID())) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                String globalPrompt = PlayerSettingsStore.getCustomPromptGlobal(
                        serverPlayer.server, serverPlayer.getUUID());
                String maidPrompt = PlayerSettingsStore.getCustomPromptForMaid(
                        serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid());
                boolean overrideEnabled = PlayerSettingsStore.isCustomPromptOverrideEnabled(
                        serverPlayer.server, serverPlayer.getUUID());
                context.reply(new CustomPromptConfigResponsePayload(
                        adminEnabled, globalPrompt, maidPrompt, overrideEnabled));
            }
        });
    }

    /**
     * 服务端：保存玩家自定义 Prompt（三种意图互斥，一次只表达一种写入）。
     * maidUuid 空 + prompt → 写全局段；maidUuid 有值 + prompt → 写单只段（校验归属）；
     * override 有值（maidUuid 必须为空）→ 写覆盖开关。
     */
    private static void handleCustomPromptSet(CustomPromptConfigSetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer && allowConfigPacket(serverPlayer.getUUID()))) {
                return;
            }
            // 管理员闸门（纵深防御）：关闭玩家自定义配置时拒绝写入，
            // 注入侧另有同闸门兜底（已落盘的 Prompt 不会生效）
            if (!Config.PLAYER_OPTION_ENABLED.get()) {
                return;
            }
            boolean hasPrompt = payload.prompt().isPresent();
            boolean hasOverride = payload.override().isPresent();
            // 意图互斥：prompt 与 override 恰有其一，否则拒绝
            if (hasPrompt == hasOverride) {
                return;
            }
            if (hasOverride) {
                // 覆盖开关是玩家级设置，不接受女仆维度（防歧义包）
                if (payload.maidUuid().isPresent()) {
                    return;
                }
                PlayerSettingsStore.setCustomPromptOverride(
                        serverPlayer.server, serverPlayer.getUUID(), payload.override().get());
                return;
            }
            String prompt = sanitizePromptText(payload.prompt().get());
            if (payload.maidUuid().isEmpty()) {
                PlayerSettingsStore.setCustomPromptGlobal(serverPlayer.server, serverPlayer.getUUID(), prompt);
            } else {
                // 归属校验：仅允许主人操作自己拥有的女仆，防伪造 UUID 污染他人数据
                if (!isOwnedMaid(serverPlayer, payload.maidUuid().get())) {
                    return;
                }
                // Prompt 单只条目数上限（防恶意客户端伪造任意 UUID 无限撑大世界存档）；
                // 已存在条目的改写不计数（与两个布尔名单 handler 的容量检查口径一致）
                if (!prompt.isBlank() && PlayerSettingsStore.getCustomPromptForMaid(
                        serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid().get()).isBlank()
                        && PlayerSettingsStore.countMaidPromptEntries(
                        serverPlayer.server, SelfTalkAttachments.LEVEL_CUSTOM_PROMPT_MAID,
                        serverPlayer.getUUID()) >= MAX_MAID_OVERRIDES) {
                    MaidSelfTalkMod.LOGGER.warn("Player {} custom prompt maid list full ({}), ignored",
                            serverPlayer.getUUID(), MAX_MAID_OVERRIDES);
                    return;
                }
                PlayerSettingsStore.setCustomPromptForMaid(
                        serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid().get(), prompt);
            }
        });
    }

    /** 服务端：响应玩家的 Tool 调用设置查询（两个管理员字段分开返回，界面提示语可区分关闸原因） */
    private static void handleToolRequest(ToolConfigRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && payload.maidUuid() != null
                    && allowConfigPacket(serverPlayer.getUUID())) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean toolAdminEnabled = Config.TOOL_CALL_ENABLED.get();
                boolean globalEnabled = PlayerSettingsStore.isToolCallGlobal(
                        serverPlayer.server, serverPlayer.getUUID());
                boolean maidEnabled = globalEnabled && !PlayerSettingsStore.isToolCallMaidDisabled(
                        serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid());
                context.reply(new ToolConfigResponsePayload(
                        adminEnabled, toolAdminEnabled, globalEnabled, maidEnabled));
            }
        });
    }

    /** 服务端：保存玩家 Tool 调用设置（maidUuid 为空 → 全局开关；非空 → 单只关闭名单） */
    private static void handleToolSet(ToolConfigSetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer && allowConfigPacket(serverPlayer.getUUID())) {
                if (payload.maidUuid().isEmpty()) {
                    PlayerSettingsStore.setToolCallGlobal(serverPlayer.server, serverPlayer.getUUID(), payload.enabled());
                } else {
                    // 归属校验：仅允许主人操作自己拥有的女仆，防伪造 UUID 污染名单
                    if (!isOwnedMaid(serverPlayer, payload.maidUuid().get())) {
                        return;
                    }
                    // 名单只存关闭项：关闭时新增条目，重新开启时移除，避免名单膨胀。
                    // 容量上限：新增键时校验，防恶意客户端伪造任意 UUID 无限撑大世界存档
                    if (!payload.enabled() && !PlayerSettingsStore.isToolCallMaidDisabled(
                            serverPlayer.server, serverPlayer.getUUID(), payload.maidUuid().get())
                            && PlayerSettingsStore.countMaidOverrides(serverPlayer.server,
                            SelfTalkAttachments.LEVEL_TOOL_CALL_MAID_OVERRIDES, serverPlayer.getUUID()) >= MAX_MAID_OVERRIDES) {
                        MaidSelfTalkMod.LOGGER.warn("Player {} tool-call maid override list full ({}), ignored",
                                serverPlayer.getUUID(), MAX_MAID_OVERRIDES);
                        return;
                    }
                    PlayerSettingsStore.setToolCallMaidDisabled(serverPlayer.server,
                            serverPlayer.getUUID(), payload.maidUuid().get(), !payload.enabled());
                }
            }
        });
    }

    /**
     * Prompt 写入清洗：长度截断 500、去除 NUL 与 CR（防超大包与控制字符污染存档）。
     * 段标签/零宽字符的剥除不在此处——那是请求注入时的职责（SegmentTags.stripTagsFromPlayerInput），
     * 界面与存档保留玩家原文。
     */
    private static String sanitizePromptText(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replace("\0", "").replace("\r", "");
        if (cleaned.length() > MAX_CUSTOM_PROMPT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_CUSTOM_PROMPT_LENGTH);
        }
        return cleaned;
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

    /** 客户端：收到自定义 Prompt 设置响应 */
    private static void handleCustomPromptResponse(CustomPromptConfigResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SelfTalkPlayerSettingsClient.onCustomPromptConfigResponse(payload));
    }

    /** 客户端：收到 Tool 调用设置响应 */
    private static void handleToolResponse(ToolConfigResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SelfTalkPlayerSettingsClient.onToolConfigResponse(payload));
    }
}
