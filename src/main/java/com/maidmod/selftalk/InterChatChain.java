package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次<b>连续互聊链</b>的运行时上下文（仅服务端内存，不持久化）。
 * <p>
 * 链身份用显式的 {@code chainId} 标识，不能只凭「还是同一对女仆」判断同一次会话——
 * 同一对女仆随时可能开新链，而旧链的迟到回调仍在途中。
 * <p>
 * 每位参与女仆保存：
 * <ul>
 *   <li>首次检索结果：{@code recallCompleted}（含「结果为空」）与召回的块，后续该女仆直接复用，
 *       不再规划、不再跑 BM25；</li>
 *   <li>本次请求是否已真正派发（关键词规划阶段不算「已经发出的正式回复」）；</li>
 *   <li>是否被主人插话禁言（旧回复不显示、不播报、不写窗口）。</li>
 * </ul>
 * <p>
 * 链自然结束、达到轮数上限、失败、死亡／卸载、主人插话中断时释放。
 * 释放只影响后续按 {@code chainId} 的查找，已在途回调持有本对象的直接引用，
 * 因此释放后仍能按中断状态正确判定交付许可。
 * <p>
 * 线程约定：全部访问在服务端主线程。
 */
public final class InterChatChain {

    /** chainId -> 链（仅服务端主线程访问） */
    private static final Map<Long, InterChatChain> CHAINS = new HashMap<>();
    /** 女仆实体 ID -> 当前所在链 ID（同一时刻一只女仆至多参与一条链） */
    private static final Map<Integer, Long> ACTIVE_BY_MAID = new HashMap<>();
    /** 链 ID 分配器（进程内单调递增，0 保留给「非互聊」） */
    private static long nextChainId = 1;

    private final long chainId;
    /** 链成员实体 ID（创建时的双方），用于卸载清理时对称释放 */
    private final int firstMaidId;
    private final int secondMaidId;
    /** 每位参与女仆的链内状态 */
    private final Map<Integer, Participant> participants = new HashMap<>();
    /** 整条链是否已被主人插话中断（中断后不再续接） */
    private boolean interrupted;

    private InterChatChain(long chainId, EntityMaid first, EntityMaid second) {
        this.chainId = chainId;
        this.firstMaidId = first.getId();
        this.secondMaidId = second.getId();
    }

    /** 每位参与女仆的链内状态 */
    public static final class Participant {
        /** 该女仆在本链的首次检索是否已完成（「结果为空」也算完成） */
        private boolean recallCompleted;
        /** 首次召回的历史块（每项是一块按历史顺序排列的消息） */
        private List<List<LLMMessage>> blocks = List.of();
        /** 是否已进入过正式生成阶段（含成功与失败）：链的两轮交接判据 */
        private boolean formalPhase;
        /** 是否被主人插话禁言：旧回复不得显示、播报、写窗口 */
        private boolean suppressed;

        /** 链上首次检索的成品结果；{@code completed} 为 false 时不得当作「结果为空」复用 */
        public HistoryContextSession.CachedRecall cachedRecall() {
            if (!recallCompleted) {
                return null;
            }
            return new HistoryContextSession.CachedRecall(true, blocks);
        }

        /** 记下本链首次检索的成品结果（为空也是成品） */
        private void completeRecall(List<List<LLMMessage>> recalled) {
            this.recallCompleted = true;
            this.blocks = recalled == null ? List.of() : List.copyOf(recalled);
        }

        /** 本链该女仆是否已进入过正式生成阶段 */
        public boolean formalPhase() {
            return formalPhase;
        }

        /** 是否被主人插话禁言 */
        public boolean suppressed() {
            return suppressed;
        }
    }

    // ===== 注册表 =====

    /** 新建一条链并登记双方（同一次连续互聊只在发起时调用一次） */
    public static InterChatChain create(EntityMaid first, EntityMaid second) {
        long id = nextChainId++;
        InterChatChain chain = new InterChatChain(id, first, second);
        CHAINS.put(id, chain);
        ACTIVE_BY_MAID.put(chain.firstMaidId, id);
        ACTIVE_BY_MAID.put(chain.secondMaidId, id);
        return chain;
    }

    /** 按链 ID 取链；已释放或 ID 无效返回 null */
    public static InterChatChain of(long id) {
        return id == 0 ? null : CHAINS.get(id);
    }

    /** 该女仆当前所在的活动链；不在任何链中返回 null */
    public static InterChatChain activeFor(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        Long id = ACTIVE_BY_MAID.get(maid.getId());
        return id == null ? null : CHAINS.get(id);
    }

    /**
     * 释放一条链。
     * <p>
     * 只当登记表里该女仆指向的仍是本链时才清除映射：新链可能在旧链释放之前就已建立，
     * 无条件按实体 ID 删除会把新链的活动映射误删。
     */
    public static void release(InterChatChain chain) {
        if (chain == null) {
            return;
        }
        releaseById(chain.chainId);
    }

    /** 按链 ID 释放（续接请求被吞、派发失败等只有 ID 可用的路径） */
    public static void releaseById(long chainId) {
        InterChatChain chain = CHAINS.remove(chainId);
        if (chain == null) {
            return;
        }
        clearActive(chain.firstMaidId, chainId);
        clearActive(chain.secondMaidId, chainId);
    }

    private static void clearActive(int maidId, long chainId) {
        Long current = ACTIVE_BY_MAID.get(maidId);
        if (current != null && current == chainId) {
            ACTIVE_BY_MAID.remove(maidId);
        }
    }

    /** 女仆死亡／卸载／状态清扫时释放其所在链（双方一并结束） */
    public static void onMaidRemoved(int maidId) {
        Long id = ACTIVE_BY_MAID.get(maidId);
        if (id != null) {
            releaseById(id);
        }
    }

    /** 服务器停止时整体释放（进程内存而已，显式清掉便于整合包反复重载） */
    public static void dropAll() {
        CHAINS.clear();
        ACTIVE_BY_MAID.clear();
        nextChainId = 1;
    }

    /** 当前活动链数（自检与诊断用） */
    static int size() {
        return CHAINS.size();
    }

    // ===== 链内状态 =====

    public long id() {
        return chainId;
    }

    /** 该女仆在本链的链内状态（首次访问即建立） */
    public Participant participant(EntityMaid maid) {
        return participants.computeIfAbsent(maid.getId(), id -> new Participant());
    }

    /** 整条链是否已被主人插话中断 */
    public boolean interrupted() {
        return interrupted;
    }

    /** 本链是否只由这两只女仆组成（派发前复核配对是否漂移到别的链） */
    boolean matches(EntityMaid a, EntityMaid b) {
        if (a == null || b == null) {
            return false;
        }
        int x = a.getId();
        int y = b.getId();
        return (x == firstMaidId && y == secondMaidId) || (x == secondMaidId && y == firstMaidId);
    }

    /**
     * 主人向链上某只女仆发起真实派发的主动聊天时调用：整条链标记中断，目标女仆禁言。
     * <p>
     * 对另一只不在此处判定——它的交付许可在回调侧按「正式请求是否已派发」单独判定，
     * 因为「尚在关键词规划阶段」不构成已经发出的正式回复。
     */
    public void interruptByOwnerChat(EntityMaid target) {
        this.interrupted = true;
        participant(target).suppressed = true;
    }

    /** 该女仆的旧回调是否仍允许产生可见副作用（气泡／播报／广播／写窗口／同步对方） */
    public boolean canDeliver(EntityMaid maid) {
        return !participant(maid).suppressed;
    }

    /** 中断后是否允许续接下一轮（唯一判据：整条链未被中断） */
    public boolean canChain() {
        return !interrupted;
    }

    /**
     * 该女仆在本链是否已进入过正式生成阶段。
     * <p>
     * 用于链的两轮交接：只有「对方已进入过正式生成（无论成败）」才允许派发下一轮，
     * 否则对方的规划／索引阶段就可能被本轮的快速交付抢跑，两条请求交错写同一窗口。
     */
    public boolean formalPhase(EntityMaid maid) {
        return participant(maid).formalPhase;
    }

    /** 标记该女仆已进入正式生成阶段（在互聊派发口真正提交请求后调用） */
    public void markFormalPhase(EntityMaid maid) {
        participant(maid).formalPhase = true;
    }

    /** 撤销正式生成阶段标记（请求实际未发出：插话中断、同步抛异常等，链不必等这一轮） */
    public void clearFormalPhase(EntityMaid maid) {
        participant(maid).formalPhase = false;
    }

    /** 登记该女仆本链的首次检索结果（同一女仆只生效一次） */
    public void completeRecall(EntityMaid maid, List<List<LLMMessage>> recalled) {
        Participant participant = participant(maid);
        if (!participant.recallCompleted) {
            participant.completeRecall(recalled);
        }
    }
}
