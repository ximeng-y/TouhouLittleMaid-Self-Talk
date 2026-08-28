package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Maps;

import java.util.Map;

/**
 * 自话/互聊派发闸门：唯一入口负责「立即派发 / 顺延入队 / 吞请求」，
 * 保证每只女仆同一时刻至多只有一个在途 LLM 请求（头上至多一个「少女思考中」）。
 * <p>
 * 所有方法都在服务端主线程调用（MaidTickEvent 与经 runOnServerThread 调度的回调）。
 * 顺延队列中的请求只保存「意图」描述（不预构建消息），派发时才调用
 * {@link MaidSelfTalkService} / {@link MaidInterChatService} 现构建消息，
 * 确保上下文（历史/互聊窗口/情境）不被交叉打乱。
 * <p>
 * 吞请求规则：
 * <ol>
 *   <li>顺延队列超出上限 → 丢弃本次请求（队列过多才吞，避免挤压自然触发队列）；</li>
 *   <li>自话与互聊冲突 → 互聊优先：入队自话时若已有互聊在途/在队则丢弃自话，
 *       入队互聊时清掉队内自话（「互相聊天顶掉自言自语」）。</li>
 * </ol>
 * 多女仆互聊对锁：A 对 C 发起互聊后，二者在「连续互聊结束」前互相对锁——
 * 不能发起、也不能被发起互聊（其它女仆的随机候选池不再包含这对）。
 */
public final class SelfTalkDispatcher {

    /** 互聊对锁：女仆实体 ID -> 锁定截止 tick（仅服务端主线程访问；链自然结束即提前解除） */
    private static final Map<Integer, Long> INTER_CHAT_LOCK_UNTIL = Maps.newHashMap();

    private SelfTalkDispatcher() {
    }

    // ===== 派发入口 =====

    /** 自话（含欢迎语）派发请求：空闲立即派发，忙则顺延，冲突/满则吞 */
    public static void requestSelfTalk(EntityMaid maid, boolean welcome, int keep, double broadcastRange) {
        submit(maid, new SelfTalkState.DeferredRequest(
                SelfTalkState.RequestKind.SELF_TALK, null, null, welcome, keep, broadcastRange, 0));
    }

    /** 互聊发起者派发请求 */
    public static void requestInterChatInitiator(EntityMaid initiator, EntityMaid responder, double broadcastRange) {
        submit(initiator, new SelfTalkState.DeferredRequest(
                SelfTalkState.RequestKind.INTER_CHAT_INITIATOR, responder, null, false, 0, broadcastRange, 1));
    }

    /** 互聊回答者（链式续接）派发请求 */
    public static void requestInterChatResponder(EntityMaid responder, EntityMaid initiator,
                                                 String peerText, double broadcastRange, int chainRound) {
        submit(responder, new SelfTalkState.DeferredRequest(
                SelfTalkState.RequestKind.INTER_CHAT_RESPONDER, initiator, peerText, false, 0, broadcastRange, chainRound));
    }

    // ===== 入队与派发 =====

    /** 按吞请求规则入队，空闲则立即派发 */
    private static void submit(EntityMaid maid, SelfTalkState.DeferredRequest req) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        boolean isInterChat = req.kind() != SelfTalkState.RequestKind.SELF_TALK;

        if (isInterChat) {
            // 互聊优先：清掉队内自话（互相聊天顶掉自言自语）
            state.deferredRequests.removeIf(r -> r.kind() == SelfTalkState.RequestKind.SELF_TALK);
        } else if (state.interChatPending || hasQueuedInterChat(state)) {
            // 自话与互聊冲突（互聊在途或已在队）：吞掉自话
            return;
        }
        if (state.deferredRequests.size() >= Config.DEFER_QUEUE_MAX.get()) {
            // 队列已满：吞请求
            return;
        }
        if (isBusy(state)) {
            state.deferredRequests.addLast(req);
        } else {
            dispatchNow(maid, req);
        }
    }

    /** 空闲时从顺延队列出队派发，直到忙或队列空 */
    public static void drainDeferred(EntityMaid maid) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        while (!isBusy(state) && !state.deferredRequests.isEmpty()) {
            SelfTalkState.DeferredRequest req = state.deferredRequests.pollFirst();
            dispatchNow(maid, req);
        }
    }

    private static boolean hasQueuedInterChat(SelfTalkState.State state) {
        for (SelfTalkState.DeferredRequest r : state.deferredRequests) {
            if (r.kind() != SelfTalkState.RequestKind.SELF_TALK) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBusy(SelfTalkState.State state) {
        return state.selfTalkPending || state.interChatPending || state.playerChatCount > 0;
    }

    /**
     * 派发单个请求：按种类现构建消息并发送，返回是否派发成功（女仆死亡/AI 关闭等失败返回 false）。
     * 互聊请求派发成功后同时续上/延长互聊对锁。
     */
    private static boolean dispatchNow(EntityMaid maid, SelfTalkState.DeferredRequest req) {
        if (!maid.isAlive()) {
            return false;
        }
        switch (req.kind()) {
            case SELF_TALK -> {
                return MaidSelfTalkService.triggerSelfTalk(maid, req.welcome(), req.keep(), req.broadcastRange());
            }
            case INTER_CHAT_INITIATOR, INTER_CHAT_RESPONDER -> {
                if (req.peer() == null || !req.peer().isAlive()) {
                    return false;
                }
                boolean ok;
                if (req.kind() == SelfTalkState.RequestKind.INTER_CHAT_INITIATOR) {
                    ok = MaidInterChatService.triggerInitiator(maid, req.peer(), req.broadcastRange());
                } else {
                    ok = MaidInterChatService.triggerResponder(maid, req.peer(), req.peerText(), req.broadcastRange(), req.chainRound());
                }
                if (ok) {
                    lockPair(maid, req.peer(), maid.level().getServer().getTickCount());
                }
                return ok;
            }
            default -> {
                return false;
            }
        }
    }

    // ===== 互聊对锁 =====

    /** 锁定一对女仆的互聊（发起者首次派发与链上每跳续接都调用，持续顺延/延长锁定时长） */
    public static void lockPair(EntityMaid a, EntityMaid b, long nowTick) {
        long until = nowTick + Config.INTER_CHAT_PAIR_LOCK_SECONDS.get() * 20L;
        INTER_CHAT_LOCK_UNTIL.put(a.getId(), until);
        INTER_CHAT_LOCK_UNTIL.put(b.getId(), until);
    }

    /** 解除一对女仆的互聊锁（链自然结束时调用） */
    public static void unlockPair(EntityMaid a, EntityMaid b) {
        if (a != null) {
            INTER_CHAT_LOCK_UNTIL.remove(a.getId());
        }
        if (b != null) {
            INTER_CHAT_LOCK_UNTIL.remove(b.getId());
        }
    }

    /** 女仆是否处于互聊对锁中（锁定期内不能发起/被发起互聊） */
    public static boolean isMaidInterChatLocked(EntityMaid maid, long nowTick) {
        Long until = INTER_CHAT_LOCK_UNTIL.get(maid.getId());
        return until != null && until > nowTick;
    }

    /** 女仆死亡/卸载时清理其互聊锁 */
    public static void onMaidRemoved(int maidId) {
        INTER_CHAT_LOCK_UNTIL.remove(maidId);
    }
}
