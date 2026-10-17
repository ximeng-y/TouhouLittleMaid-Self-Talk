package com.maidmod.selftalk.persona;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.network.MaidPersonaSplitRequestPayload;
import com.maidmod.selftalk.network.MaidPersonaSplitResponsePayload;
import com.maidmod.selftalk.network.SelfTalkPackets;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.PacketDistributor;

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
 * 但整理在途本身是「该女仆忙」的一种：{@link #isSplitting(UUID)} 供派发闸门与人设保存互斥共用，
 * 只拦新派发，不打断整理开始前已在途的聊天或规划。
 * <p>
 * 超时（{@value #TIMEOUT_TICKS} tick）通过全局服务器 tick 清扫实现——刻意不依赖女仆 tick，
 * 否则区块卸载后任务永远无法收尾。女仆死亡／卸载同样取消任务（回「不可用」失败），
 * 全局扫描另按 UUID 复查存活，作为未收到离开事件时的兜底。
 * 登出取消该玩家的任务，服务器停止取消全部任务，两者都不发通知。
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
     * 该女仆是否有在途整理任务（服务端主线程）：派发闸门与人设保存互斥共用同一个查询。
     * <p>
     * 只遍历现有任务表并匹配未终态任务——不额外维护第二张状态表或计数器，
     * 任务的登记与摘除本身就是唯一事实来源。
     */
    public static boolean isSplitting(UUID maidUuid) {
        if (maidUuid == null || TASKS.isEmpty()) {
            return false;
        }
        for (Task task : TASKS.values()) {
            if (!task.finished && maidUuid.equals(task.maidUuid)) {
                return true;
            }
        }
        return false;
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
            reply(player, MaidPersonaSplitResponsePayload.failure(requestId, maidUuid,
                    MaidPersonaSplitResponsePayload.RESULT_RATE_LIMITED));
            return;
        }
        if (TASKS.containsKey(player.getUUID())) {
            reply(player, MaidPersonaSplitResponsePayload.failure(requestId, maidUuid,
                    MaidPersonaSplitResponsePayload.RESULT_BUSY));
            return;
        }
        if (!(player.serverLevel().getEntity(entityId) instanceof EntityMaid maid)
                || maidUuid == null || !maid.getUUID().equals(maidUuid)) {
            reply(player, MaidPersonaSplitResponsePayload.failure(requestId, maidUuid,
                    MaidPersonaSplitResponsePayload.RESULT_NOT_LOADED));
            return;
        }
        if (!maid.isOwnedBy(player)) {
            reply(player, MaidPersonaSplitResponsePayload.failure(requestId, maidUuid,
                    MaidPersonaSplitResponsePayload.RESULT_NOT_OWNER));
            return;
        }
        // 同一女仆只允许一个整理任务：实体身份与归属校验通过后再判，
        // 与「每名玩家最多一个任务」并存（同一玩家的不同连接/另一玩家都不能重复整理同一只女仆）
        if (isSplitting(maidUuid)) {
            reply(player, MaidPersonaSplitResponsePayload.failure(requestId, maidUuid,
                    MaidPersonaSplitResponsePayload.RESULT_BUSY));
            return;
        }
        String source = sourceText == null ? "" : sourceText;
        if (source.isBlank()) {
            reply(player, MaidPersonaSplitResponsePayload.failure(requestId, maidUuid,
                    MaidPersonaSplitResponsePayload.RESULT_EMPTY_SOURCE));
            return;
        }
        if (source.length() > MaidPersonaSplitRequestPayload.MAX_SOURCE_LENGTH) {
            reply(player, MaidPersonaSplitResponsePayload.failure(requestId, maidUuid,
                    MaidPersonaSplitResponsePayload.RESULT_TOO_LONG));
            return;
        }
        // TLM AI 可用性：AI 开关 + 当前配置的 LLM 站点（拆分沿用该女仆当前配置的模型）
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null || !AIConfig.LLM_ENABLED.get()) {
            reply(player, MaidPersonaSplitResponsePayload.failure(requestId, maidUuid,
                    MaidPersonaSplitResponsePayload.RESULT_UNAVAILABLE));
            return;
        }
        LLMSite site = chatManager.getLLMSite();
        if (site == null || !site.enabled() || site.client() == null) {
            reply(player, MaidPersonaSplitResponsePayload.failure(requestId, maidUuid,
                    MaidPersonaSplitResponsePayload.RESULT_UNAVAILABLE));
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
                    MaidPersonaSplitResponsePayload.RESULT_NETWORK));
        }
    }

    /** 任务终态（服务端主线程）：解除登记、按需回包并私信，只处理一次 */
    private static void finish(Task task, PersonaSplitCallback.Outcome outcome) {
        if (!detach(task)) {
            return;
        }
        deliver(task, outcome);
    }

    /**
     * 取消任务（超时／女仆死亡或卸载共用）：先摘除登记，再静默作废上游请求，最后按失败交付。
     * <p>
     * 摘除在前是刻意的：任务一旦进入终态就不该再改变忙状态，也不该被迟到的上游回调二次收尾。
     */
    private static void cancel(Task task, String resultCode) {
        if (!detach(task)) {
            return;
        }
        if (task.callback != null) {
            task.callback.completeSilently();
        }
        deliver(task, PersonaSplitCallback.Outcome.failure(resultCode));
    }

    /**
     * 一次性摘除任务登记：按玩家 UUID 与 Task 对象身份条件移除。
     * <p>
     * 已经终态、或已被同一玩家的新任务顶替的旧任务返回 false——不得回包、不得清除新任务的登记，
     * 也不得改变新任务的忙状态。
     */
    private static boolean detach(Task task) {
        if (task.finished) {
            return false;
        }
        task.finished = true;
        TASKS.remove(task.player.getUUID(), task);
        return true;
    }

    /** 交付结果（任务已摘除）：候选超限与女仆失效都在这里收敛，再按连接身份回包并私信 */
    private static void deliver(Task task, PersonaSplitCallback.Outcome outcome) {
        // 交付前再确认女仆仍存活：死亡/卸载的清扫可能还没跑到，任务不能抢先交付成功
        if (outcome.ok() && !isMaidLoaded(task)) {
            outcome = PersonaSplitCallback.Outcome.failure(
                    MaidPersonaSplitResponsePayload.RESULT_NOT_LOADED);
        }
        // 候选合计超过传输上限：明确失败，不返回截断结果
        if (outcome.ok() && outcome.draft().totalLength() > MAX_CANDIDATE_TOTAL) {
            outcome = PersonaSplitCallback.Outcome.failure(
                    MaidPersonaSplitResponsePayload.RESULT_TOO_LONG);
        }
        // 只交付给仍是同一连接会话的发起玩家：重生换实体但会话不变照常交付；
        // 已离线或重连（新连接会话）不补发
        ServerPlayer current = task.player.server.getPlayerList().getPlayer(task.player.getUUID());
        if (current == null || current.connection != task.connection) {
            return;
        }
        MaidPersonaSplitResponsePayload response = outcome.ok()
                ? MaidPersonaSplitResponsePayload.ok(task.requestId, task.maidUuid, outcome.draft())
                : MaidPersonaSplitResponsePayload.failure(task.requestId, task.maidUuid,
                outcome.resultCode());
        // 结果包先发送，再发送私信（同一主线程批内顺序固定）
        PacketDistributor.sendToPlayer(current, response);
        current.sendSystemMessage(outcome.ok()
                ? Component.translatable("chat.maid_self_talk.split.done", task.maidName)
                : Component.translatable("chat.maid_self_talk.split.failed"));
    }

    /**
     * 女仆是否仍存活于服务器任一维度。
     * <p>
     * 按 UUID 在全部维度查找，因此跨维度正常加载（实体已在另一维度）照常算可用；
     * 缺失、已死亡或已进入移除流程都算不可用。
     */
    private static boolean isMaidLoaded(Task task) {
        MinecraftServer server = task.player.server;
        if (server == null) {
            return false;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(task.maidUuid) instanceof EntityMaid maid
                    && maid.isAlive() && !maid.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 全局服务器 tick 清扫：先按 UUID 复查女仆存活（未收到离开事件、女仆 tick 已停止时的兜底），
     * 再按 6000 tick 时限收尾超时任务。
     */
    public static void onServerTick(MinecraftServer server) {
        if (TASKS.isEmpty()) {
            return;
        }
        long nowTick = server.getTickCount();
        // 快照遍历：收尾会改任务表，边遍历边改会破坏 HashMap 的迭代
        for (Task task : List.copyOf(TASKS.values())) {
            if (!isMaidLoaded(task)) {
                cancel(task, MaidPersonaSplitResponsePayload.RESULT_NOT_LOADED);
                continue;
            }
            if (nowTick - task.startTick <= TIMEOUT_TICKS) {
                continue;
            }
            cancel(task, MaidPersonaSplitResponsePayload.RESULT_TIMEOUT);
        }
    }

    /**
     * 女仆死亡／卸载：取消其整理任务，向仍在线的原连接回「女仆不可用」失败。
     * <p>
     * 由女仆 tick 的死亡分支与离开维度分支调用；正常跨维度传送不算卸载，不经过这里。
     */
    public static void onMaidRemoved(UUID maidUuid) {
        if (maidUuid == null || TASKS.isEmpty()) {
            return;
        }
        for (Task task : List.copyOf(TASKS.values())) {
            if (maidUuid.equals(task.maidUuid)) {
                cancel(task, MaidPersonaSplitResponsePayload.RESULT_NOT_LOADED);
            }
        }
    }

    /** 玩家登出：取消该连接发起的任务（玩家已离开，不发任何通知） */
    public static void onPlayerLogout(UUID playerUuid) {
        Task task = TASKS.get(playerUuid);
        if (task == null || !detach(task)) {
            return;
        }
        if (task.callback != null) {
            task.callback.completeSilently();
        }
    }

    /** 服务器停止：取消全部在途任务（静默，不发通知）；快照遍历，收尾即摘除登记 */
    public static void onServerStopped() {
        for (Task task : List.copyOf(TASKS.values())) {
            if (!detach(task)) {
                continue;
            }
            if (task.callback != null) {
                task.callback.completeSilently();
            }
        }
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
    private static void reply(ServerPlayer player, MaidPersonaSplitResponsePayload response) {
        PacketDistributor.sendToPlayer(player, response);
    }
}
