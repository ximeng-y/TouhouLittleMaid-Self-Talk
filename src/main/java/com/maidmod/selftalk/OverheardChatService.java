package com.maidmod.selftalk;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 附近玩家聊天的采集服务：同步来源作用域 + 唯一采集入口。
 * <p>
 * 来源作用域用标准库 {@link ThreadLocal}，只跨<b>本次同步广播</b>，不跨异步任务：
 * 「普通聊天」在 {@code ServerGamePacketListenerImpl.broadcastChatMessage} 的包装里建立，
 * 「玩家 /say」在 {@code PlayerList} 的命令来源广播重载包装里建立；
 * 两者都只在合格时建立，均由 {@code PlayerList} 最终公共广播 TAIL 一次性消费。
 * <p>
 * 采集点是最终公共广播 TAIL：此时消息确实已发出，正文取服务端共同广播的纯文本
 * （不取命令原串、不取签名内容、不重新装饰）。控制台、命令方块与冒用玩家实体的执行者
 * 从不建立有效来源，因此不记录、不占编号。
 */
public final class OverheardChatService {

    /** 当前线程的采集来源作用域（嵌套调用保存／恢复前一层） */
    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    /** 来源种类与本次实际发送者 */
    private record Scope(OverheardChatStore.Source source, ServerPlayer player, MinecraftServer server,
                         boolean consumed) {

        Scope consume() {
            return new Scope(source, player, server, true);
        }
    }

    private OverheardChatService() {
    }

    // ===== 来源作用域 =====

    /** 普通玩家聊天：由包监听器的广播包装建立 */
    public static ScopeHandle beginNormalChat(ServerPlayer player) {
        return begin(OverheardChatStore.Source.NORMAL_CHAT, player);
    }

    /** 玩家本人执行的 {@code /say}：由已解析广播回调包装建立；不合格时返回 null 且不建立作用域 */
    public static ScopeHandle beginPlayerSay(ServerPlayer player) {
        return begin(OverheardChatStore.Source.PLAYER_SAY, player);
    }

    private static ScopeHandle begin(OverheardChatStore.Source source, ServerPlayer player) {
        if (player == null || player.level().getServer() == null) {
            return null;
        }
        Scope previous = SCOPE.get();
        SCOPE.set(new Scope(source, player, player.level().getServer(), false));
        return new ScopeHandle(previous);
    }

    /**
     * 一次性作用域句柄：{@link #close()} 必须在 {@code finally} 中调用，恢复外层作用域。
     * <p>
     * 句柄不可重复关闭；嵌套调用（如包装内的包装）各自持有独立句柄，互不覆盖。
     */
    public static final class ScopeHandle implements AutoCloseable {
        private final Scope previous;
        private boolean closed;

        private ScopeHandle(Scope previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(previous);
            }
        }
    }

    // ===== 唯一采集入口 =====

    /**
     * 最终公共广播 TAIL 调用：消费本线程的有效来源一次并采集。
     * <p>
     * 无有效来源（控制台、命令方块、冒用执行者、其他 mod 的直接广播）直接返回；
     * 关闭总开关时停止采集新的偷听聊天，已有记忆保留。
     *
     * @param content 本次实际广播对象的纯正文
     */
    public static void onBroadcastCompleted(String content) {
        Scope scope = SCOPE.get();
        if (scope == null || scope.consumed()) {
            return;
        }
        SCOPE.set(scope.consume());
        if (!Config.ENABLED.get() || content == null || content.isBlank()) {
            return;
        }
        ServerPlayer player = scope.player();
        MinecraftServer server = scope.server();
        if (server == null || player.level().getServer() != server || !player.isAlive()) {
            return;
        }
        try {
            collect(server, player, content, scope.source());
        } catch (Throwable t) {
            // 采集不影响原聊天；异常只记录，不向广播路径抛出
            MaidSelfTalkMod.LOGGER.warn("Failed to record overheard player chat", t);
        }
    }

    /** 在实际发送玩家所在维度按球形距离筛选存活女仆，每只独立按获取概率保存锚点 */
    private static void collect(MinecraftServer server, ServerPlayer player, String content,
                                OverheardChatStore.Source source) {
        OverheardChatStore store = OverheardChatSavedData.storeOf(server);
        if (store == null) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        double radius = Config.OVERHEARD_HEARING_RADIUS.get();
        double radiusSqr = radius * radius;
        AABB box = player.getBoundingBox().inflate(radius);
        double acquireProbability = Config.OVERHEARD_ACQUIRE_PROBABILITY.get();
        List<UUID> heard = new ArrayList<>();
        for (var entity : level.getEntities(player, box)) {
            if (!(entity instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid)) {
                continue;
            }
            if (!maid.isAlive() || maid.isRemoved()) {
                continue;
            }
            if (maid.distanceToSqr(player) > radiusSqr) {
                continue;
            }
            if (maid.getRandom().nextDouble() < acquireProbability) {
                heard.add(maid.getUUID());
            }
        }
        long now = System.currentTimeMillis();
        long id = store.recordQualified(player.getUUID(), player.getGameProfile().getName(), now, content,
                source, heard);
        OverheardChatSavedData.get(server).markDirtyIfChanged();
        MaidSelfTalkMod.LOGGER.debug("Recorded overheard chat {} for {} maids (source {})",
                id, heard.size(), source);
    }
}
