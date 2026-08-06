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

    /** 女仆死亡/卸载时清理状态 */
    public static void cleanupIfDead(int maidId, boolean alive) {
        if (!alive) {
            STATES.remove(maidId);
        }
    }

    public static final class State {
        /** 下次可触发自话的 gameTime（tick） */
        public long nextTriggerTick = 0;
        /** 是否有自话正在进行（回复未返回） */
        public boolean selfTalkPending = false;
        /** 是否有玩家 chat 正在进行 */
        public boolean playerChatPending = false;
        /** 当前自话窗口内已保留的自言自语 assistant 消息（按时间序） */
        public final List<LLMMessage> windowSelfTalkMsgs = new ArrayList<>();
        /** 本女仆已欢迎过的玩家 */
        public final java.util.Set<UUID> welcomedPlayers = new java.util.HashSet<>();
    }
}
