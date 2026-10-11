package com.maidmod.selftalk;

/**
 * 本 mod 请求（自话／欢迎语／互聊）的轻量请求令牌：与清空世代绑定。
 * <p>
 * 手动清空（{@code clearAllChatMemory}）会推进该女仆的「清空世代」，于是清空前已经开始的一切
 * 在途请求立即失效——迟到结果不得写档案、不得写窗口、不得发回复事件、不得广播，也不得续接下一轮；
 * 它们只清理自己持有的资源。清空之后发起的新请求捕获新的世代号，照常工作。
 * <p>
 * 令牌在会话开始时捕获（<b>不</b>延迟到最后发送时才捕获）：检索模式的会话可能在规划阶段
 * 停留很久，若在正式派发时才取世代号，清空恰好发生在规划期间就会被漏掉。
 * <p>
 * <b>生命周期</b>：世代计数器由 {@link SelfTalkState.State} 持有，令牌<b>按引用</b>共享它，
 * 不再另存一份以实体 ID 为键的全局世代映射。这样世代记录与状态条目同生共死：
 * <ul>
 *   <li>状态条目被移除（死亡／卸载／周期清扫）时计数器一并退役，此前捕获的令牌永久失效——
 *       状态条目消失不会让旧令牌重新被判为有效；</li>
 *   <li>状态条目若随后重建，拿到的是全新计数器（从 0 开始），新请求不会被已删除状态的清空记录误伤。</li>
 * </ul>
 * 因此不再需要「世代墓碑」这类旁路记录，也就不存在墓碑与状态条目各处一半的清理不一致。
 * <p>
 * 线程约定：世代号只在服务端主线程推进；令牌的 {@link #isValid()} 允许响应线程读取
 * （工具异步入口用它做线程安全的失效判定），因此计数器用 volatile 字段而非普通字段，
 * 且令牌在创建时快照当时的世代值——判定只读快照与计数器本身，不触碰状态表的普通 Map。
 */
public final class SelfTalkRequestToken {

    private final int maidId;
    /** 捕获时所在的世代计数器：状态条目被移除即退役，持有它的令牌随之永久失效 */
    private final Generation generation;
    /** 捕获时的世代值：计数器推进到它之上即视为已被清空作废 */
    private final int capturedGeneration;

    private SelfTalkRequestToken(int maidId, Generation generation) {
        this.maidId = maidId;
        this.generation = generation;
        this.capturedGeneration = generation.value;
    }

    /**
     * 在服务端主线程捕获当前世代令牌（请求开始的那一刻）。
     * <p>
     * 用不创建状态的查询：请求可能来自已卸载女仆的迟到路径，不该为其重建状态表条目。
     * 状态不存在时给一个独立计数器——该请求与任何清空记录都无关，不会被误判作废。
     */
    public static SelfTalkRequestToken capture(int maidId) {
        SelfTalkState.State state = SelfTalkState.peek(maidId);
        return new SelfTalkRequestToken(maidId,
                state == null ? new Generation() : state.clearGeneration);
    }

    /**
     * 本请求是否仍然有效（未被清空作废，且所属状态未退役）。
     * <p>
     * 线程安全：只读创建时快照的世代值与该计数器自身的 volatile 字段，
     * 因此响应线程的工具异步入口可以安全调用。
     */
    public boolean isValid() {
        return !generation.retired && capturedGeneration >= generation.value;
    }

    /** 女仆实体 ID（诊断用） */
    public int maidId() {
        return maidId;
    }

    /**
     * 清空世代计数器：由 {@link SelfTalkState.State} 持有，令牌按引用共享。
     * <p>
     * 只增不减、只退不还——推进后此前捕获的令牌全部失效，退役后全部永久失效。
     * 重建的状态拿到的是新实例，与已移除状态上的清空记录互不影响。
     */
    public static final class Generation {

        /** 当前世代值（服务端主线程写，响应线程读） */
        private volatile int value;
        /** 是否已随状态条目移除而退役：退役后所有持有者永久失效 */
        private volatile boolean retired;

        /** 服务端主线程：手动清空时推进，令此前捕获的令牌失效 */
        void advance() {
            value++;
        }

        /** 服务端主线程：状态条目被移除时退役，令全部在途令牌永久失效 */
        void retire() {
            retired = true;
        }
    }
}
