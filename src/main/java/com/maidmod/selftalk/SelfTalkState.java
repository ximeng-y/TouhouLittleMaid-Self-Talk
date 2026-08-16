package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.google.common.collect.Maps;

import java.util.ArrayList;
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

    public static final class State {
        /** 下次可触发自话的 gameTime（tick） */
        public long nextTriggerTick = 0;
        /** 是否有自话正在进行（回复未返回） */
        public boolean selfTalkPending = false;
        /**
         * 在途玩家 chat 数（TLM 无并发护栏，玩家可连发多条 chat；
         * 计数而非布尔，避免先完成的回复提前清掉标记导致自话与在途 chat 交错写历史）
         */
        public int playerChatCount = 0;
        /** 当前自话窗口内已保留的自言自语 assistant 消息（按时间序） */
        public final List<LLMMessage> windowSelfTalkMsgs = new ArrayList<>();
        /** 本女仆已欢迎过的玩家 */
        public final java.util.Set<UUID> welcomedPlayers = new java.util.HashSet<>();
    }
}
