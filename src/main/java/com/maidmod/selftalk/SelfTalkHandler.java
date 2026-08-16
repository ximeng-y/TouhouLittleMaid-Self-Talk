package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Maps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端自话状态机。
 * <p>
 * 触发条件（全部满足才触发一次自话）：
 * <ol>
 *   <li>总开关开启、TLM AI 开关开启、LLM site 可用、女仆有人设（无则跳过，不自动生成）；</li>
 *   <li>无正在进行的自话/玩家 chat（防止交错写历史）；</li>
 *   <li>冷却期已过（间隔在配置区间内随机，每次触发后重新随机）；</li>
 *   <li>无主人女仆不触发；有主人的按态判定：</li>
 * </ol>
 * 态 1（主人在线）：受态 1 配置约束，并检查该主人的玩家独立设置；
 * 态 2（主人离线但附近有玩家）：受态 2 配置约束。
 * <p>
 * 欢迎逻辑：主人登录后的窗口期内触发（无半径限制，加载区块内的女仆均可），
 * 每只女仆对每名主人仅欢迎一次。
 */
public final class SelfTalkHandler {

    /** 玩家 UUID -> 登录时刻（服务器 tick，与欢迎窗口判定同基准） */
    private static final Map<UUID, Long> PLAYER_LOGIN_TICKS = Maps.newHashMap();

    /** 欢迎语秒级闸门：上次放行的服务器秒（serverTick / 20）与该秒内已放行次数（仅服务端主线程访问） */
    private static long lastDispatchSecond = -1;
    private static int dispatchCountThisSecond = 0;

    /** 自话闸门：下次允许自话放行的服务器 tick（仅服务端主线程访问） */
    private static long nextSelfTalkAllowedTick = 0;

    /** 被限流自话的随机退避区间（tick）：8~15 秒，仅内部重试、不发请求 */
    private static final int BACKOFF_MIN_TICKS = 8 * 20;
    private static final int BACKOFF_MAX_TICKS = 15 * 20;

    /** pending 超时阈值（tick）：5 分钟，回调永不返回时强制复位防卡死 */
    private static final long PENDING_TIMEOUT_TICKS = 5 * 60 * 20;

    /** 状态周期清扫间隔（tick）：5 分钟（兜底清理卸载女仆的残留状态） */
    private static final long CLEANUP_INTERVAL_TICKS = 5 * 60 * 20;
    /** 上次状态清扫的服务器 tick */
    private static long lastCleanupTick = 0;

    private SelfTalkHandler() {
    }

    @SubscribeEvent
    public static void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        // 注意：绝不取消该事件（取消会中断女仆自身的 tick 逻辑）。
        // 同时本方法绝不能向外抛异常——MaidTickEvent 的异常会导致实体 tick 崩溃，
        // 整合包/服务端的实体崩溃恢复机制会直接移除女仆实体。
        try {
            tick(maid);
        } catch (Throwable t) {
            MaidSelfTalkMod.LOGGER.error("SelfTalkHandler tick error for maid {}", maid.getId(), t);
        }
    }

    private static void tick(EntityMaid maid) {
        if (!Config.ENABLED.get()) {
            return;
        }
        if (!(maid.level() instanceof ServerLevel level)) {
            return;
        }
        // 周期性清扫（任何女仆 tick 时触发）：清理已不加载实体的残留状态。
        // 区块卸载/死亡的女仆不再收到 MaidTickEvent，仅靠死亡路径清理会随实体流转无限增长
        long serverTick = level.getServer().getTickCount();
        if (serverTick - lastCleanupTick >= CLEANUP_INTERVAL_TICKS) {
            lastCleanupTick = serverTick;
            cleanupStaleStates(level.getServer());
        }
        if (!maid.isAlive()) {
            SelfTalkState.cleanupIfDead(maid.getId(), false);
            return;
        }

        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        // pending 超时兜底：回调永不返回（如 HTTP 请求挂死）时强制复位，防女仆自话/chat 永久卡死
        if (state.selfTalkPending && state.selfTalkPendingSinceTick >= 0
                && serverTick - state.selfTalkPendingSinceTick > PENDING_TIMEOUT_TICKS) {
            MaidSelfTalkMod.LOGGER.warn("Self-talk pending timed out for maid {}, force reset", maid.getId());
            state.selfTalkPending = false;
            state.selfTalkPendingSinceTick = -1;
        }
        if (state.playerChatCount > 0 && state.playerChatSinceTick >= 0
                && serverTick - state.playerChatSinceTick > PENDING_TIMEOUT_TICKS) {
            MaidSelfTalkMod.LOGGER.warn("Player chat pending timed out for maid {}, force reset", maid.getId());
            state.playerChatCount = 0;
            state.playerChatSinceTick = -1;
        }
        // 有进行中的自话或玩家 chat 时，跳过本次触发
        if (state.selfTalkPending || state.playerChatCount > 0) {
            return;
        }
        // AI 前置门槛
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null) {
            return;
        }
        if (!AIConfig.LLM_ENABLED.get()) {
            return;
        }
        LLMSite site = chatManager.getLLMSite();
        if (site == null || !site.enabled()) {
            return;
        }
        // 无人设（无自定义设定且无模型默认设定）→ 不触发，不自动生成
        if (chatManager.customSetting.isBlank() && chatManager.getSetting().isEmpty()) {
            return;
        }
        UUID ownerUuid = maid.getOwnerUUID();
        // 无主人的女仆不触发
        if (ownerUuid == null) {
            return;
        }

        // 欢迎检查（优先于自话）：主人登录窗口期内、未欢迎过该主人
        if (Config.WELCOME_ENABLED.get() && !state.welcomedPlayers.contains(ownerUuid)) {
            Long loginTick = PLAYER_LOGIN_TICKS.get(ownerUuid);
            // 主人必须仍在线：玩家已全部退出时不再触发欢迎（避免无玩家空耗 token）
            // 欢迎语同样受玩家设置约束：全局或单只关闭时跳过（不标记 welcomed，
            // 窗口期内每 tick 自然重试，窗口过期放弃），与自话语义一致
            if (loginTick != null && maid.getOwner() != null
                    && serverTick - loginTick <= Config.WELCOME_WINDOW_TICKS.get()) {
                if (Config.PLAYER_OPTION_ENABLED.get() && !isSelfTalkEnabledForMaid(maid, level)) {
                    return;
                }
                // 欢迎语闸门未放行则不标记、不发请求，窗口期内每 tick 自然重试
                if (!tryAcquireWelcomeSlot(serverTick)) {
                    return;
                }
                boolean triggered = MaidSelfTalkService.triggerSelfTalk(maid, true,
                        Config.STATE1_KEEP_SELF_TALK_COUNT.get(), Config.STATE1_PLAYER_RANGE.get());
                if (triggered) {
                    // 仅在真实发起后才标记：触发失败（如清洗/接入异常）时窗口期内可继续重试
                    state.welcomedPlayers.add(ownerUuid);
                    applyCooldown(state, serverTick,
                            Config.STATE1_MIN_INTERVAL.get(), Config.STATE1_MAX_INTERVAL.get());
                }
                return;
            }
        }

        // 冷却期
        if (serverTick < state.nextTriggerTick) {
            return;
        }

        // 态判定：主人在线 → 态 1；主人离线 → 附近有玩家才触发（态 2）
        boolean ownerOnline = maid.getOwner() != null;
        if (ownerOnline) {
            if (!Config.STATE1_ENABLED.get()) {
                return;
            }
            // 玩家独立设置：管理员允许玩家配置时，检查该女仆主人及其单只名单
            if (Config.PLAYER_OPTION_ENABLED.get() && !isSelfTalkEnabledForMaid(maid, level)) {
                return;
            }
            if (!hasPlayerNearby(maid, Config.STATE1_PLAYER_RANGE.get())) {
                return;
            }
            if (!tryAcquireSelfTalkSlot(serverTick)) {
                // 自话闸门未放行：随机退避 8~15 秒再试，不发请求
                state.nextTriggerTick = serverTick + BACKOFF_MIN_TICKS
                        + (int) (Math.random() * (BACKOFF_MAX_TICKS - BACKOFF_MIN_TICKS + 1));
                return;
            }
            boolean triggered = MaidSelfTalkService.triggerSelfTalk(maid, false,
                    Config.STATE1_KEEP_SELF_TALK_COUNT.get(), Config.STATE1_PLAYER_RANGE.get());
            if (triggered) {
                applyCooldown(state, serverTick,
                        Config.STATE1_MIN_INTERVAL.get(), Config.STATE1_MAX_INTERVAL.get());
            }
        } else {
            if (!Config.STATE2_ENABLED.get()) {
                return;
            }
            // 主人离线，无玩家独立设置可查，直接按管理员配置
            if (!hasPlayerNearby(maid, Config.STATE2_PLAYER_RANGE.get())) {
                return;
            }
            if (!tryAcquireSelfTalkSlot(serverTick)) {
                // 自话闸门未放行：随机退避 8~15 秒再试，不发请求
                state.nextTriggerTick = serverTick + BACKOFF_MIN_TICKS
                        + (int) (Math.random() * (BACKOFF_MAX_TICKS - BACKOFF_MIN_TICKS + 1));
                return;
            }
            boolean triggered = MaidSelfTalkService.triggerSelfTalk(maid, false,
                    Config.STATE2_KEEP_SELF_TALK_COUNT.get(), Config.STATE2_PLAYER_RANGE.get());
            if (triggered) {
                applyCooldown(state, serverTick,
                        Config.STATE2_MIN_INTERVAL.get(), Config.STATE2_MAX_INTERVAL.get());
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            // 记录服务器全局 tick，与欢迎窗口判定的计时基准一致（跨维度统一）
            PLAYER_LOGIN_TICKS.put(serverPlayer.getUUID(), (long) serverPlayer.server.getTickCount());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        PLAYER_LOGIN_TICKS.remove(uuid);
        // 清掉所有女仆对该玩家的欢迎标记：每次登录的欢迎窗口内欢迎一次
        SelfTalkState.removeWelcomeForPlayer(uuid);
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        // 玩家死亡重生：复制独立设置（1.20.1 方案 B 天然保留，此处对齐语义）
        if (event.isWasDeath() && event.getOriginal() instanceof ServerPlayer oldPlayer) {
            ServerPlayer newPlayer = (ServerPlayer) event.getEntity();
            if (oldPlayer.hasData(SelfTalkAttachments.SELF_TALK_ENABLED)) {
                newPlayer.setData(SelfTalkAttachments.SELF_TALK_ENABLED,
                        oldPlayer.getData(SelfTalkAttachments.SELF_TALK_ENABLED));
            }
            // 单只关闭名单同样保留；拷贝副本，避免新旧玩家共享同一 map 实例
            if (oldPlayer.hasData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES)) {
                newPlayer.setData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES,
                        new HashMap<>(oldPlayer.getData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES)));
            }
        }
    }

    /** 半径内是否存在存活、非旁观模式的玩家 */
    private static boolean hasPlayerNearby(EntityMaid maid, double range) {
        AABB box = maid.getBoundingBox().inflate(range);
        List<ServerPlayer> players = maid.level().getEntitiesOfClass(ServerPlayer.class, box,
                p -> p.isAlive() && !p.isSpectator());
        return !players.isEmpty();
    }

    /**
     * 读取女仆自话有效值：全局开关 && 单只关闭名单不包含该女仆。
     * 仅态 1（主人在线）与欢迎语使用；态 2 主人离线查不到设置，按管理员配置。
     */
    private static boolean isSelfTalkEnabledForMaid(EntityMaid maid, ServerLevel level) {
        UUID ownerUuid = maid.getOwnerUUID();
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid);
        if (owner == null) {
            // 主人在线判定刚通过但此处查不到（极端时序），按启用处理
            return true;
        }
        if (!owner.getData(SelfTalkAttachments.SELF_TALK_ENABLED)) {
            return false;
        }
        // 用 getExistingData 读取：避免无名单时惰性安装空 map 进存档
        return owner.getExistingData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES)
                .map(overrides -> !overrides.containsKey(maid.getUUID().toString()))
                .orElse(true);
    }

    /**
     * 女仆离开维度时即时清理状态（EntityLeaveLevelEvent）。
     * 传送也会触发离开事件（实体随后加入新维度），按移除原因排除；
     * 若事件触发时 removalReason 尚未设置（时序差异）则此处跳过，
     * 由周期性清扫 {@link #cleanupStaleStates} 兜底，不影响正确性。
     */
    @SubscribeEvent
    public static void onMaidLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel
                && event.getEntity() instanceof EntityMaid maid
                && maid.isRemoved()
                && maid.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION) {
            SelfTalkState.cleanupIfDead(maid.getId(), false);
        }
    }

    /** 清扫 STATES 中已不加载于任何维度的实体条目（服务端主线程调用） */
    private static void cleanupStaleStates(MinecraftServer server) {
        // 先收集再删除：遍历中直接 remove 会触发 HashMap fail-fast 的 ConcurrentModificationException
        List<Integer> staleIds = new ArrayList<>();
        for (Map.Entry<Integer, SelfTalkState.State> entry : SelfTalkState.entrySet()) {
            if (!isEntityLoaded(server, entry.getKey())) {
                staleIds.add(entry.getKey());
            }
        }
        for (int maidId : staleIds) {
            SelfTalkState.remove(maidId);
        }
    }

    /** 实体 ID 是否仍加载于任意服务端维度 */
    private static boolean isEntityLoaded(MinecraftServer server, int entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(entityId) != null) {
                return true;
            }
        }
        return false;
    }

    /** 触发成功后设置冷却：区间内随机（tick），每次触发后重新随机 */
    private static void applyCooldown(SelfTalkState.State state, long serverTick, int minSeconds, int maxSeconds) {
        state.nextTriggerTick = serverTick + Config.randomIntervalTicks(minSeconds, maxSeconds);
    }

    /**
     * 欢迎语秒级闸门：本秒（serverTick / 20）额度未用完才放行，放行即消耗一个额度。
     * 被限流时调用方必须放弃本次触发（不发请求），窗口期内每 tick 自然重试。
     */
    private static boolean tryAcquireWelcomeSlot(long serverTick) {
        long second = serverTick / 20;
        if (second != lastDispatchSecond) {
            lastDispatchSecond = second;
            dispatchCountThisSecond = 0;
        }
        if (dispatchCountThisSecond >= Config.MAX_TRIGGER_PER_SECOND.get()) {
            return false;
        }
        dispatchCountThisSecond++;
        return true;
    }

    /**
     * 自话全局闸门：距上次自话放行随机 5~8 秒（可配）后才放行下一只。
     * 被限流时调用方必须放弃本次触发（不发请求），仅内部退避重试。
     */
    private static boolean tryAcquireSelfTalkSlot(long serverTick) {
        if (serverTick < nextSelfTalkAllowedTick) {
            return false;
        }
        nextSelfTalkAllowedTick = serverTick + Config.randomIntervalTicks(
                Config.SELF_TALK_MIN_INTERVAL.get(), Config.SELF_TALK_MAX_INTERVAL.get());
        return true;
    }
}
