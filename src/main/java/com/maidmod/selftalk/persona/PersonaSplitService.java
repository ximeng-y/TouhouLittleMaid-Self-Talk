package com.maidmod.selftalk.persona;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.network.MaidPersonaSplitRequestMessage;
import com.maidmod.selftalk.network.MaidPersonaSplitResponseMessage;
import com.maidmod.selftalk.network.SelfTalkPackets;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「AI 拆分人设」服务端任务管理（所有状态只在服务端主线程访问）。
 * <p>
 * 每名玩家同时最多一个整理任务：重复请求返回 {@code busy}，不排队不覆盖。
 * 任务冻结发起时刻的原文、模型站点与发起连接；结果（成功/失败/超时）按任务身份
 * 只处理一次，回包先发、私信后发，且只发给仍是同一连接会话的发起玩家——
 * 重生更换的玩家实体复用同一连接会话（{@code ServerPlayer.connection}），照常交付；
 * 连接断开后重连得到的是新连接会话，不向其补发。
 * <p>
 * 请求不经过自话/互聊闸门，不占用其 pending、对锁、历史规划或取消登记：
 * {@link PersonaSplitCallback} 是完全独立的静默回调，不写任何聊天状态。
 * <p>
 * 超时（{@value #TIMEOUT_TICKS} tick）通过全局服务器 tick 清扫实现——刻意不依赖女仆 tick，
 * 否则区块卸载后任务永远无法收尾。登出取消该玩家的任务，服务器停止取消全部任务。
 */
public final class PersonaSplitService {

    /** 单次整理超时（服务器 tick）：6000 tick = 5 分钟 */
    private static final long TIMEOUT_TICKS = 6000;

    /** 候选四栏合计传输上限：超过则明确失败，不返回截断结果（保存仍按 4096 上限另行校验） */
    public static final int MAX_CANDIDATE_TOTAL = 16384;

    /** 玩家 UUID -> 在途整理任务（每名玩家同时最多一个） */
    private static final Map<UUID, Task> TASKS = new HashMap<>();

    /** 一个在途整理任务 */
    private static final class Task {
        final UUID requestId;
        final UUID maidUuid;
        /** 发起时刻冻结的女仆名称（完成时实体可能已卸载，不能届时再取） */
        final String maidName;
        /** 发起玩家实例：跨重生仍持有有效 server 引用，仅供主线程调度使用 */
        final ServerPlayer player;
        /** 发起时刻冻结的连接会话：重生换实体但复用同一实例，重连（或已离线）才是新会话 */
        final ServerGamePacketListenerImpl connection;
        final long startTick;
        PersonaSplitCallback callback;
        boolean finished;

        Task(UUID requestId, UUID maidUuid, String maidName, ServerPlayer player, long startTick) {
            this.requestId = requestId;
            this.maidUuid = maidUuid;
            this.maidName = maidName;
            this.player = player;
            this.connection = player.connection;
            this.startTick = startTick;
        }
    }

    private PersonaSplitService() {
    }

    /**
     * 处理拆分请求（服务端主线程）。校验失败一律回固定结果码（与保存链同构），
     * 不回包即丢弃的情况只有 requestId 缺失一种。
     */
    public static void handleSplitRequest(ServerPlayer player, UUID requestId, int entityId,
                                          UUID maidUuid, String sourceText) {
        if (requestId == null) {
            // 请求 id 缺失则回包无从匹配，直接丢弃
            return;
        }
        if (!SelfTalkPackets.allowConfigPacket(player.getUUID())) {
            reply(player, MaidPersonaSplitResponseMessage.failure(requestId, maidUuid,
                    MaidPersonaSplitResponseMessage.RESULT_RATE_LIMITED));
            return;
        }
        if (TASKS.containsKey(player.getUUID())) {
            reply(player, MaidPersonaSplitResponseMessage.failure(requestId, maidUuid,
                    MaidPersonaSplitResponseMessage.RESULT_BUSY));
            return;
        }
        if (!(player.serverLevel().getEntity(entityId) instanceof EntityMaid maid)
                || maidUuid == null || !maid.getUUID().equals(maidUuid)) {
            reply(player, MaidPersonaSplitResponseMessage.failure(requestId, maidUuid,
                    MaidPersonaSplitResponseMessage.RESULT_NOT_LOADED));
            return;
        }
        if (!player.getUUID().equals(maid.getOwnerUUID())) {
            reply(player, MaidPersonaSplitResponseMessage.failure(requestId, maidUuid,
                    MaidPersonaSplitResponseMessage.RESULT_NOT_OWNER));
            return;
        }
        String source = sourceText == null ? "" : sourceText;
        if (source.isBlank()) {
            reply(player, MaidPersonaSplitResponseMessage.failure(requestId, maidUuid,
                    MaidPersonaSplitResponseMessage.RESULT_EMPTY_SOURCE));
            return;
        }
        if (source.length() > MaidPersonaSplitRequestMessage.MAX_SOURCE_LENGTH) {
            reply(player, MaidPersonaSplitResponseMessage.failure(requestId, maidUuid,
                    MaidPersonaSplitResponseMessage.RESULT_TOO_LONG));
            return;
        }
        // TLM AI 可用性：AI 开关 + 当前配置的 LLM 站点（拆分沿用该女仆当前配置的模型）
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null || !AIConfig.LLM_ENABLED.get()) {
            reply(player, MaidPersonaSplitResponseMessage.failure(requestId, maidUuid,
                    MaidPersonaSplitResponseMessage.RESULT_UNAVAILABLE));
            return;
        }
        LLMSite site = chatManager.getLLMSite();
        if (site == null || !site.enabled() || site.client() == null) {
            reply(player, MaidPersonaSplitResponseMessage.failure(requestId, maidUuid,
                    MaidPersonaSplitResponseMessage.RESULT_UNAVAILABLE));
            return;
        }
        startTask(player, requestId, maid, maidUuid, site, chatManager, source);
    }

    /** 冻结任务并发起静默请求（不重试、不做格式纠正：每次用户操作只发一次业务请求） */
    private static void startTask(ServerPlayer player, UUID requestId, EntityMaid maid, UUID maidUuid,
                                  LLMSite site, MaidAIChatManager chatManager, String source) {
        Task task = new Task(requestId, maidUuid, maid.getName().getString(), player,
                player.server.getTickCount());
        PersonaSplitCallback callback = new PersonaSplitCallback(chatManager,
                List.of(LLMMessage.systemChat(maid, PersonaSplitPrompts.SPLIT_SYSTEM),
                        LLMMessage.userChat(maid, PersonaSplitPrompts.buildSourceMessage(source))),
                outcome -> onServerThread(task, () -> finish(task, outcome)));
        task.callback = callback;
        TASKS.put(player.getUUID(), task);
        try {
            site.client().chat(callback);
        } catch (Throwable t) {
            // 传输阶段就抛异常（site 配置非法等）：按网络失败收尾，任务已登记故正常走终态
            MaidSelfTalkMod.LOGGER.warn("Failed to dispatch persona split request for maid {}",
                    maid.getId(), t);
            finish(task, PersonaSplitCallback.Outcome.failure(
                    MaidPersonaSplitResponseMessage.RESULT_NETWORK));
        }
    }

    /** 任务终态（服务端主线程）：解除登记、按需回包并私信，只处理一次 */
    private static void finish(Task task, PersonaSplitCallback.Outcome outcome) {
        if (task.finished) {
            return;
        }
        task.finished = true;
        TASKS.remove(task.player.getUUID());
        // 候选合计超过传输上限：明确失败，不返回截断结果
        if (outcome.ok() && outcome.draft().totalLength() > MAX_CANDIDATE_TOTAL) {
            outcome = PersonaSplitCallback.Outcome.failure(
                    MaidPersonaSplitResponseMessage.RESULT_TOO_LONG);
        }
        // 只交付给仍是同一连接会话的发起玩家：重生换实体但会话不变照常交付；
        // 已离线或重连（新连接会话）不补发
        ServerPlayer current = task.player.server.getPlayerList().getPlayer(task.player.getUUID());
        if (current == null || current.connection != task.connection) {
            return;
        }
        MaidPersonaSplitResponseMessage response = outcome.ok()
                ? MaidPersonaSplitResponseMessage.ok(task.requestId, task.maidUuid, outcome.draft())
                : MaidPersonaSplitResponseMessage.failure(task.requestId, task.maidUuid,
                outcome.resultCode());
        // 结果包先发送，再发送私信（同一主线程批内顺序固定）
        SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> current), response);
        current.sendSystemMessage(outcome.ok()
                ? Component.translatable("chat.maid_self_talk.split.done", task.maidName)
                : Component.translatable("chat.maid_self_talk.split.failed"));
    }

    /** 全局服务器 tick 清扫：超过时限的任务取消在途请求并返回超时（不依赖女仆 tick） */
    public static void onServerTick(MinecraftServer server) {
        if (TASKS.isEmpty()) {
            return;
        }
        long nowTick = server.getTickCount();
        for (Task task : List.copyOf(TASKS.values())) {
            if (nowTick - task.startTick <= TIMEOUT_TICKS) {
                continue;
            }
            if (task.callback != null) {
                task.callback.completeSilently();
            }
            finish(task, PersonaSplitCallback.Outcome.failure(
                    MaidPersonaSplitResponseMessage.RESULT_TIMEOUT));
        }
    }

    /** 玩家登出：取消该连接发起的任务（玩家已离开，不发任何通知） */
    public static void onPlayerLogout(UUID playerUuid) {
        Task task = TASKS.remove(playerUuid);
        if (task != null && task.callback != null) {
            task.callback.completeSilently();
        }
    }

    /** 服务器停止：取消全部在途任务 */
    public static void onServerStopped() {
        for (Task task : TASKS.values()) {
            if (task.callback != null) {
                task.callback.completeSilently();
            }
        }
        TASKS.clear();
    }

    /** 把响应线程的结果投递回服务端主线程；停机竞态下调度失败按放弃处理（任务已由清扫收尾） */
    private static void onServerThread(Task task, Runnable runnable) {
        MinecraftServer server = task.player.server;
        if (server == null || server.isSameThread()) {
            runnable.run();
            return;
        }
        try {
            server.execute(runnable);
        } catch (Throwable t) {
            MaidSelfTalkMod.LOGGER.warn("Persona split result dropped during shutdown for maid {}",
                    task.maidName);
        }
    }

    /** 校验失败的即时回包（仍在请求处理上下文内，直接按连接发送） */
    private static void reply(ServerPlayer player, MaidPersonaSplitResponseMessage response) {
        SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), response);
    }
}
