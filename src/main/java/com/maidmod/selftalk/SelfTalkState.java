package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Maps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每只女仆的自话运行时状态（仅存在于服务端内存，不持久化）。
 * <p>
 * 全部访问都在服务端主线程（MaidTickEvent 与经 runOnServerThread 调度的回调）完成。
 */
public final class SelfTalkState {

    /** 女仆实体 ID -> 状态 */
    private static final Map<Integer, State> STATES = Maps.newHashMap();

    private SelfTalkState() {
    }

    public static State get(int maidId) {
        return STATES.computeIfAbsent(maidId, id -> new State());
    }

    public static void remove(int maidId) {
        STATES.remove(maidId);
    }

    /** 全部状态条目（周期清扫用） */
    public static java.util.Set<Map.Entry<Integer, State>> entrySet() {
        return STATES.entrySet();
    }

    /**
     * 女仆死亡/卸载/移除时清理状态。
     * 调用方：EntityLeaveLevelEvent 即时清理与 tick 中死亡兜底。
     */
    public static void cleanupIfDead(int maidId, boolean alive) {
        if (!alive) {
            STATES.remove(maidId);
        }
    }
    /** 玩家登出：清除所有女仆对该玩家的欢迎标记，下次登录窗口内可再次欢迎 */
    public static void removeWelcomeForPlayer(UUID playerUuid) {
        for (State state : STATES.values()) {
            state.welcomedPlayers.remove(playerUuid);
        }
    }

    /** 待派发请求的种类（顺延队列条目） */
    public enum RequestKind { SELF_TALK, INTER_CHAT_INITIATOR, INTER_CHAT_RESPONDER }

    /**
     * 顺延队列中的待派发请求描述（仅存意图，不预构建消息）。
     * <p>
     * 派发时才由 {@link MaidSelfTalkService} / {@link MaidInterChatService} 现构建消息，
     * 确保历史/互聊窗口/情境等上下文按派发时刻的最新状态生成，不被积压期间的交叉所打乱。
     */
    public record DeferredRequest(
            RequestKind kind,
            EntityMaid peer,
            String peerText,
            int keep,
            double broadcastRange,
            int chainRound) {
    }

    public static final class State {
        /** 下次可触发自话的服务器 tick（全局单调，与 server.getTickCount() 同基准） */
        public long nextTriggerTick = 0;
        /** 是否有自话正在进行（回复未返回） */
        public boolean selfTalkPending = false;
        /** selfTalkPending 置位时的服务器 tick（-1 = 未置位），用于超时强制复位防卡死 */
        public long selfTalkPendingSinceTick = -1;
        /**
         * 在途玩家 chat 数（TLM 无并发护栏，玩家可连发多条 chat；
         * 计数而非布尔，避免先完成的回复提前清掉标记导致自话与在途 chat 交错写历史）
         */
        public int playerChatCount = 0;
        /** playerChatCount 最近一次自增时的服务器 tick（-1 = 未置位），用于超时强制复位防卡死 */
        public long playerChatSinceTick = -1;
        /** 当前自话窗口内已保留的自言自语 assistant 消息（按时间序） */
        public final List<LLMMessage> windowSelfTalkMsgs = new ArrayList<>();
        /** 本女仆已欢迎过的玩家 */
        public final java.util.Set<UUID> welcomedPlayers = new java.util.HashSet<>();

        // ===== 互聊状态（与自话独立） =====
        /** 下次可触发互聊的服务器 tick */
        public long nextInterChatTriggerTick = 0;
        /** 是否有互聊正在进行（回复未返回） */
        public boolean interChatPending = false;
        /** interChatPending 置位时的服务器 tick（-1 = 未置位） */
        public long interChatPendingSinceTick = -1;
        /** 当前互聊窗口内已保留的互聊 assistant 消息（按时间序，含发起与回答） */
        public final List<LLMMessage> windowInterChatMsgs = new ArrayList<>();
        /** 顺延队列：忙时积压的自话/互聊请求，空闲时按序派发（派发时现构建消息） */
        public final Deque<DeferredRequest> deferredRequests = new ArrayDeque<>();
    }
}
