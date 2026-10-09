package com.maidmod.selftalk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本 mod 请求（自话／欢迎语／互聊）的轻量请求令牌：与存储清空世代绑定。
 * <p>
 * 手动清空（{@code clearAllChatMemory}）会推进该女仆的「已清空至世代」，于是清空前已经开始的一切
 * 在途请求立即失效——迟到结果不得写档案、不得写窗口、不得发回复事件、不得广播，也不得续接下一轮；
 * 它们只清理自己持有的资源。清空之后发起的新请求捕获新的世代号，照常工作。
 * <p>
 * 令牌在会话开始时捕获（<b>不</b>延迟到最后发送时才捕获）：检索模式的会话可能在规划阶段
 * 停留很久，若在正式派发时才取世代号，清空恰好发生在规划期间就会被漏掉。
 * <p>
 * 线程约定：世代号只在服务端主线程推进；令牌的 {@link #isValid()} 允许响应线程读取
 * （工具异步入口用它做线程安全的失效判定），因此这里不读 {@link SelfTalkState} 的普通 Map，
 * 而是把世代号在创建时快照进令牌，并只查一个并发映射。
 */
public final class SelfTalkRequestToken {

    /** 女仆实体 ID -> 已清空至的世代（令牌世代小于它即视为已被清空作废） */
    private static final Map<Integer, Integer> CLEARED_UP_TO = new ConcurrentHashMap<>();

    private final int maidId;
    private final int generation;

    private SelfTalkRequestToken(int maidId, int generation) {
        this.maidId = maidId;
        this.generation = generation;
    }

    /**
     * 在服务端主线程捕获当前世代令牌（请求开始的那一刻）。
     * <p>
     * 用不创建状态的查询：请求可能来自已卸载女仆的迟到路径，不该为其重建状态表条目。
     */
    public static SelfTalkRequestToken capture(int maidId) {
        SelfTalkState.State state = SelfTalkState.peek(maidId);
        return new SelfTalkRequestToken(maidId, state == null ? 0 : state.clearGeneration);
    }

    /**
     * 服务端主线程：手动清空时推进「已清空至世代」，令此前捕获的一切令牌失效。
     * <p>
     * 只增不减：同一世代内的多次清空取最大推进值，不会被后来的较小值回退。
     */
    static void advanceClearedUpTo(int maidId, int generation) {
        CLEARED_UP_TO.merge(maidId, generation, Math::max);
    }

    /**
     * 本请求是否仍然有效（未被清空作废）。
     * <p>
     * 线程安全：只读创建时快照的世代号与一个并发映射，不触碰状态表普通 Map，
     * 因此响应线程的工具异步入口可以安全调用。
     */
    public boolean isValid() {
        Integer clearedUpTo = CLEARED_UP_TO.get(maidId);
        return clearedUpTo == null || generation >= clearedUpTo;
    }

    /** 女仆实体 ID（诊断用） */
    public int maidId() {
        return maidId;
    }

    /** 女仆状态被清理时一并丢弃其世代记录（防长期运行残留） */
    static void clearTombstones(int maidId) {
        CLEARED_UP_TO.remove(maidId);
    }
}
