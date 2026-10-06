package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/**
 * 某只女仆在一条互聊链上的一次<b>发言请求</b>的运行时身份（每轮每个参与者一个，
 * 仅存在于服务端内存，不持久化）。
 * <p>
 * 一次发言流程 = 「关键词规划（检索模式）→（纠正若干次）→ 正式请求 →（可用时工具连环）→ 终态」。
 * 请求身份串起这段流程的两类异步回调（规划回调与正式回调），并携带跨线程可读的停止标记：
 * <ul>
 *   <li>规划阶段被取消：规划回调的迟到结果不再导出下一步动作，不发消息、不上送 LLM；</li>
 *   <li>正式阶段被取消：迟到成功/失败不产生任何玩家可见副作用，只清理本请求自己的资源。</li>
 * </ul>
 * {@link #cancel()} 是业务作废的一次性入口（服务端主线程调用），无论处在哪个阶段都会：
 * <ol>
 *   <li>置停止标记（volatile，规划回调与正式回调可在响应线程读取）；</li>
 *   <li>终止在途的历史组装会话（检索模式：结束当前规划回调；正式模式下无在途规划请求）；</li>
 *   <li>经 {@link AgentTweaksLifecycleBridge} 静默作废正式回调（若已派发：Agent-Tweaks 取消 HTTP/排队/
 *       重试/deadline 并把迟到结果挡在公共响应调用点，本 mod 无需等待取消回调即可清理资源）；</li>
 *   <li>与全局当前请求登记比较对象身份后解除登记（旧轮不能清掉新轮登记）。</li>
 * </ol>
 * <p>
 * 线程约定：登记、阶段推进、{@link #cancel()}、终态清理都在服务端主线程（链、状态表、资源的修改
 * 统一在主线程执行）；停止标记允许响应线程的异步入口读取，但任何游戏效果判定、写状态、清资源
 * 一律投回主线程再做。
 */
public final class InterChatRequest {

    /** 女仆实体 ID -> 该女仆「当前」互聊请求（服务端主线程访问；同一时刻每只女仆至多一个在途互聊请求） */
    private static final java.util.Map<Integer, InterChatRequest> CURRENT = new java.util.HashMap<>();

    /** 链身份（呼吁体链释放后回调仍可据此判断归属，不依赖链表存活） */
    private final long chainId;
    /** 发言者（本请求所属的女仆）实体 ID */
    private final int maidId;
    /** 本请求所属的互聊链（可为 null：链已释放；登记时刻可能有、后续置空） */
    private InterChatChain chain;
    /** 本请求的历史组装会话（检索模式规划请求的停止行为经会话实施；自话等非互聊路径为 null） */
    private HistoryContextSession session;

    /** 是否已被业务作废（volatile：响应线程的异步回调仅读此标记决定是否沉默） */
    private volatile boolean cancelled;
    /** 作废/终态是否已收敛过（防迟到回调重复触发清理：只收敛一次） */
    private boolean terminal;
    /** 是否已派发过一次正式请求（规划阶段为 false；作废清理据此决定要不要清理正式回调） */
    private boolean formalDispatched;
    /** 本请求创建并派发的正式回调（作废时交给 bridge 静默取消；用后不持有） */
    private InterChatCallback formalCallback;

    private InterChatRequest(long chainId, int maidId, InterChatChain chain) {
        this.chainId = chainId;
        this.maidId = maidId;
        this.chain = chain;
    }

    /** 新建并登记为本女仆「当前」互聊请求（强制覆盖旧条目：新轮请求压在旧轮之上） */
    public static InterChatRequest registerFor(int maidId, long chainId, InterChatChain chain) {
        InterChatRequest request = new InterChatRequest(chainId, maidId, chain);
        CURRENT.put(maidId, request);
        // 状态表同步保留：链释放后仍能借它定位「即使链已释放也要作废的旧轮请求」
        //（状态表不随链释放清空；请求作废/终态时按对象身份解除登记）
        SelfTalkState.get(maidId).currentInterChatRequest = request;
        return request;
    }

    /** 该女仆当前的互聊请求（无条件；由调用方判断归属） */
    public static InterChatRequest currentFor(int maidId) {
        return CURRENT.get(maidId);
    }

    /** 该女仆当前互聊请求；仅当仍归属指定链时返回，否则 null（防跨链误用旧引用） */
    public static InterChatRequest currentForChain(int maidId, long chainId) {
        InterChatRequest request = CURRENT.get(maidId);
        return request != null && request.chainId == chainId ? request : null;
    }

    /** 女仆死亡/卸载/状态清扫时结束其当前请求（若有）并解除登记 */
    public static void onMaidRemoved(int maidId) {
        InterChatRequest request = CURRENT.remove(maidId);
        if (request != null) {
            request.cancel();
        }
    }

    // ===== 归属与阶段 =====

    public long chainId() {
        return chainId;
    }

    public int maidId() {
        return maidId;
    }

    /** 本请求所属的链（作废/清理后为 null；正常流程可能在登记后释放） */
    InterChatChain chain() {
        return chain;
    }

    /** 在链释放时置空对本链的引用（回调侧已持有链引用，此处仅防注册表长期拖着活链不放） */
    void detachChain() {
        this.chain = null;
    }

    /** 是否已被业务作废（供跨线程异步入口判断迟到结果是否必须沉默） */
    public boolean isCancelled() {
        return cancelled;
    }

    /** 是否已派发过正式请求（规划阶段为 false；作废清理据此决定要不要清理正式回调） */
    boolean isFormalDispatched() {
        return formalDispatched;
    }

    /** 绑定本次会话的历史组装会话（检索模式规划请求的停止行为经会话实施） */
    void attachSession(HistoryContextSession session) {
        this.session = session;
    }

    /** 正式请求派发完成后登记（回调创建后立即调用，为随后作废时能取到回调做准备） */
    void markFormalDispatched(InterChatCallback callback) {
        this.formalCallback = callback;
        this.formalDispatched = true;
    }

    /**
     * 上报「本请求已正常走完业务终态」给上游生命周期门面（仅链自然结束的成功交付路径调用）。
     * <p>
     * 与 {@link #cancel()} 相斥：back 后不再期待任何结果，不应再报 complete；本方法同样幂等，
     * 只把已派发的正式回调交给 bridge。作废请求不得调它（作废即失败/放弃，不算「正常完成」）——
     * 由 {@link InterChatCallback#endChainAndComplete} 在 {@code normalMaid} 分支里守卫。
     */
    void completeFormal() {
        if (formalCallback != null) {
            AgentTweaksLifecycleBridge.complete(formalCallback);
            formalCallback = null;
        }
    }

    // ===== 业务作废 =====

    /**
     * 业务判定本请求已作废（主人插话、链结束、女仆移除、超时）：一次性终止入口，幂等。
     * <p>
     * 服务端主线程调用。作废后：
     * <ul>
     *   <li>置停止标记——规划回调与正式回调可跨线程读到「必须沉默」；</li>
     *   <li>终止历史组装会话（检索模式：结束当前规划回调，杜绝迟到的规划结果继续导出消息或纠正）；</li>
     *   <li>把已派发的正式回调交给 bridge 静默作废——取消 HTTP/排队/重试/deadline 并保留取消标记，
     *       迟到成功/失败被挡在公共响应调用点；</li>
     *   <li>按对象身份解除本女仆的当前请求登记（旧轮不能清掉新轮登记）。</li>
     * </ul>
     * 链的解锁、释放与对锁解除不在本方法内：由业务作废的调用方（插话中断、超时、移除、链终结）
     * 在各自路径上完成，与「先作废、后收尾」的顺序保持一致。
     */
    public void cancel() {
        if (terminal) {
            return;
        }
        terminal = true;
        cancelled = true;
        if (session != null) {
            try {
                session.cancel();
            } catch (Throwable t) {
                MaidSelfTalkMod.LOGGER.warn("Failed to cancel inter-chat session for maid {} chain {}",
                        maidId, chainId, t);
            }
            session = null;
        }
        if (formalCallback != null) {
            AgentTweaksLifecycleBridge.cancel(formalCallback);
            formalCallback = null;
        }
        clearFromRegistry();
    }

    /** 终态收尾时解除登记：按对象身份比较，旧轮不能清掉新轮登记 */
    private void clearFromRegistry() {
        if (CURRENT.get(maidId) == this) {
            CURRENT.remove(maidId);
        }
        // 状态表同口径解除：只清掉仍指向本请求的条目，旧轮不能误清新轮
        SelfTalkState.State state = SelfTalkState.get(maidId);
        if (state.currentInterChatRequest == this) {
            state.currentInterChatRequest = null;
        }
    }
}