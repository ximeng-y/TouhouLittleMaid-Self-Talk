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
 *   <li>自话与互聊冲突 → 互聊优先：入队自话时若已有互聊在途、已在队，或本人处于互聊对锁中
 *       则丢弃自话，入队互聊时清掉队内自话（「互相聊天顶掉自言自语」）。</li>
 * </ol>
 * 多女仆互聊对锁：A 对 C 发起互聊后，二者在「连续互聊结束」前互相对锁——
 * 不能发起、也不能被发起互聊（其它女仆的随机候选池不再包含这对），期间也不触发自话。
 */
public final class SelfTalkDispatcher {

    /** 互聊对锁：女仆实体 ID -> 锁定截止 tick（仅服务端主线程访问；链自然结束即提前解除） */
    private static final Map<Integer, Long> INTER_CHAT_LOCK_UNTIL = Maps.newHashMap();
    /** 互聊对锁的配对关系：女仆实体 ID -> 对方实体 ID（用于死亡/卸载时对称释放） */
    private static final Map<Integer, Integer> INTER_CHAT_PAIR_PARTNER = Maps.newHashMap();
    /** 互聊对锁所属的链：女仆实体 ID -> chainId（迟到回调只能解除仍归属本链的对锁） */
    private static final Map<Integer, Long> INTER_CHAT_PAIR_CHAIN = Maps.newHashMap();

    private SelfTalkDispatcher() {
    }

    // ===== 派发入口 =====

    /** 自话派发请求：空闲立即派发，忙则顺延，冲突/满则吞（欢迎语为一次性、不走本闸门，见 SelfTalkHandler） */
    public static void requestSelfTalk(EntityMaid maid, int keep, double broadcastRange) {
        submit(maid, new SelfTalkState.DeferredRequest(
                SelfTalkState.RequestKind.SELF_TALK, null, null, keep, broadcastRange, 0, 0));
    }

    /**
     * 互聊发起者派发请求：同时新建一条互聊链。
     * <p>
     * 链标识在<b>入队前</b>生成——顺延派发时也要带着同一个 chainId，
     * 才能与后续续接请求（以及主人插话判定）对应到同一次会话。
     * 请求被吞或派发失败时链立即释放，不留悬挂条目。
     */
    public static void requestInterChatInitiator(EntityMaid initiator, EntityMaid responder, double broadcastRange) {
        InterChatChain chain = InterChatChain.create(initiator, responder);
        boolean accepted = submit(initiator, new SelfTalkState.DeferredRequest(
                SelfTalkState.RequestKind.INTER_CHAT_INITIATOR, responder, null, 0, broadcastRange, 1,
                chain.id()));
        if (!accepted) {
            InterChatChain.release(chain);
        }
    }

    /** 互聊回答者（链式续接）派发请求：沿用本链的 chainId */
    public static void requestInterChatResponder(EntityMaid responder, EntityMaid initiator,
                                                 String peerText, double broadcastRange, int chainRound,
                                                 long chainId) {
        submit(responder, new SelfTalkState.DeferredRequest(
                SelfTalkState.RequestKind.INTER_CHAT_RESPONDER, initiator, peerText, 0, broadcastRange,
                chainRound, chainId));
    }

    // ===== 入队与派发 =====

    /**
     * 按吞请求规则入队，空闲则立即派发。
     *
     * @return 请求是否被受理（入队或已派发）；false 表示被吞，调用方需自行释放随请求创建的资源
     */
    private static boolean submit(EntityMaid maid, SelfTalkState.DeferredRequest req) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        boolean isInterChat = req.kind() != SelfTalkState.RequestKind.SELF_TALK;

        if (isInterChat) {
            // 互聊优先：清掉队内自话（互相聊天顶掉自言自语）
            state.deferredRequests.removeIf(r -> r.kind() == SelfTalkState.RequestKind.SELF_TALK);
            // 同一女仆只保留一条互聊请求：本女仆又拿到新互聊任务时，队里的旧互聊请求必然已失效
            // （链已结束、回复已返回或被插话），留在队里只会占位并拖到超时
            state.deferredRequests.removeIf(r -> r.kind() != SelfTalkState.RequestKind.SELF_TALK);
        } else if (state.interChatPending || hasQueuedInterChat(state)
                || isMaidInterChatLocked(maid, maid.level().getServer().getTickCount())) {
            // 自话与互聊冲突（互聊在途/已在队/本人处于互聊对锁中）：吞掉自话。
            // 对锁同样算「互聊进行中」——链上交接的间隙本人 pending 已清、队列已空，
            // 但锁仍在（对方正在生成回复），此时放行自话即与互聊交替进行，故一并吞掉
            return false;
        }
        if (state.deferredRequests.size() >= Config.DEFER_QUEUE_MAX.get()) {
            // 队列已满：吞请求
            return false;
        }
        if (isBusy(maid, state)) {
            state.deferredRequests.addLast(req);
            return true;
        }
        dispatchNow(maid, req);
        return true;
    }

    /**
     * 丢弃该女仆顺延队列中属于指定互聊链的请求（主人插话中断时调用）。
     * 只按 chainId 匹配：新链的请求不是「旧链的续接」，绝不能被一起清掉。
     */
    public static void dropQueuedForChain(EntityMaid maid, long chainId) {
        if (maid == null || chainId == 0) {
            return;
        }
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.deferredRequests.removeIf(r -> r.chainId() == chainId);
    }

    /** 空闲时从顺延队列出队派发，直到忙或队列空 */
    public static void drainDeferred(EntityMaid maid) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        while (!isBusy(maid, state) && !state.deferredRequests.isEmpty()) {
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

    /**
     * 忙判定：pending/玩家 chat 在途，或女仆睡觉且玩家开启「睡觉时安静」。
     * <p>
     * 睡眠归入忙（而非派发失败）——顺延队列只出队不丢弃、submit 改为入队，
     * 睡眠期间"已获批的发言"保留到醒后派发；与「玩家 chat 在途则顺延」同语义。
     */
    private static boolean isBusy(EntityMaid maid, SelfTalkState.State state) {
        if (maid.isSleeping() && PlayerSettingsStore.isSleepQuietForMaid(maid.level().getServer(), maid)) {
            return true;
        }
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
                // 对锁期间不派发自话：顺延队列里的自话可能在本人被锁定之后才轮到出队
                // （锁由「对方发起互聊」的派发挂上，那一刻不会清理本人队内的自话），
                // 此处兜底丢弃，与 submit 的冲突规则同语义
                if (isMaidInterChatLocked(maid, maid.level().getServer().getTickCount())) {
                    return false;
                }
                return MaidSelfTalkService.triggerSelfTalk(maid, false, req.keep(), req.broadcastRange());
            }
            case INTER_CHAT_INITIATOR, INTER_CHAT_RESPONDER -> {
                long nowTick = maid.level().getServer().getTickCount();
                // 链已中断或已释放（主人插话、链结束、失败）：请求作废，不得重新创建该链。
                // 已中断／已漂移的链在此统一释放注册表，不残留 CHAINS/ACTIVE_BY_MAID 阻塞后续配对
                InterChatChain chain = InterChatChain.of(req.chainId());
                if (chain == null || chain.interrupted() || !chain.matches(maid, req.peer())) {
                    unlockPairIfPaired(maid, req.peer(), nowTick, req.chainId());
                    InterChatChain.release(chain);
                    return false;
                }
                if (req.peer() == null || !req.peer().isAlive()) {
                    unlockPairIfPaired(maid, req.peer(), nowTick, req.chainId());
                    InterChatChain.release(chain);
                    return false;
                }
                // 派发前双方复核(发起者/回答者路径对等):顺延期间 peer 可能已被他人锁定/进入
                // 在途/入睡(睡眠归入忙后链续接请求可滞至整夜,对锁早到期,不能依赖锁兜底);
                // 复核失败即断链并解锁,避免陈旧请求派发给已漂移的配对导致两条链交错写同一窗口。
                // 注意锁语义:peer 与本人的配对锁=链进行中(放行);被他人锁定=漂移(断链)
                SelfTalkState.State peerState = SelfTalkState.get(req.peer().getId());
                Integer peerPartner = currentPairPartner(req.peer(), nowTick);
                if (peerState.selfTalkPending || peerState.interChatPending || peerState.playerChatCount > 0
                        || (req.peer().isSleeping()
                        && PlayerSettingsStore.isSleepQuietForMaid(req.peer().level().getServer(), req.peer()))
                        || (peerPartner != null && !peerPartner.equals(maid.getId()))) {
                    // 仅当双方当前仍互为配对才解锁:漂移后旧链对锁已被新链 lockPair 前置清理,
                    // 无条件 unlockPair 会误拆第三方在途新链(不能触碰不属于本对的锁)
                    unlockPairIfPaired(maid, req.peer(), nowTick, req.chainId());
                    InterChatChain.release(chain);
                    return false;
                }
                // 自身若已与他链配对(配对者不是当前 peer):放弃本请求,保留新链锁不动
                Integer ownPartner = currentPairPartner(maid, nowTick);
                if (ownPartner != null && !ownPartner.equals(req.peer().getId())) {
                    return false;
                }
                boolean ok;
                if (req.kind() == SelfTalkState.RequestKind.INTER_CHAT_INITIATOR) {
                    ok = MaidInterChatService.triggerInitiator(maid, req.peer(), req.broadcastRange(), chain);
                } else {
                    ok = MaidInterChatService.triggerResponder(maid, req.peer(), req.peerText(),
                            req.broadcastRange(), req.chainRound(), chain);
                }
                if (ok) {
                    lockPair(maid, req.peer(), maid.level().getServer().getTickCount(), chain.id());
                } else {
                    // 派发失败（AI 中途失效等）：链已断，解除本对旧锁并释放链
                    unlockPair(maid, req.peer());
                    InterChatChain.release(chain);
                }
                return ok;
            }
            default -> {
                return false;
            }
        }
    }

    // ===== 互聊对锁 =====

    /** 锁定一对女仆的互聊（发起者首次派发与链上每跳续接都调用，持续顺延/延长锁定时长）。
     * 携带本链的 chainId：迟到回调/陈旧请求只能解除仍归属本链的对锁，不能误拆新链的对锁 */
    public static void lockPair(EntityMaid a, EntityMaid b, long nowTick, long chainId) {
        clearPairFor(a.getId());
        clearPairFor(b.getId());
        long until = nowTick + Config.INTER_CHAT_PAIR_LOCK_SECONDS.get() * 20L;
        INTER_CHAT_LOCK_UNTIL.put(a.getId(), until);
        INTER_CHAT_LOCK_UNTIL.put(b.getId(), until);
        INTER_CHAT_PAIR_PARTNER.put(a.getId(), b.getId());
        INTER_CHAT_PAIR_PARTNER.put(b.getId(), a.getId());
        INTER_CHAT_PAIR_CHAIN.put(a.getId(), chainId);
        INTER_CHAT_PAIR_CHAIN.put(b.getId(), chainId);
    }

    /** 解除一对女仆的互聊锁（链自然结束/请求失败时调用） */
    public static void unlockPair(EntityMaid a, EntityMaid b) {
        if (a != null) {
            clearPairFor(a.getId());
        }
        if (b != null) {
            clearPairFor(b.getId());
        }
    }

    /** 女仆是否处于互聊对锁中（锁定期内不能发起/被发起互聊） */
    public static boolean isMaidInterChatLocked(EntityMaid maid, long nowTick) {
        Long until = INTER_CHAT_LOCK_UNTIL.get(maid.getId());
        if (until == null) {
            return false;
        }
        if (until > nowTick) {
            return true;
        }
        // 锁已过期：顺带清理自身及配对条目，防长期运行残留
        clearPairFor(maid.getId());
        return false;
    }

    /** 女仆当前配对的对方实体 ID（无锁/已过期返回 null；过期时顺带清理配对条目） */
    private static Integer currentPairPartner(EntityMaid maid, long nowTick) {
        Long until = INTER_CHAT_LOCK_UNTIL.get(maid.getId());
        if (until == null || until <= nowTick) {
            clearPairFor(maid.getId());
            return null;
        }
        return INTER_CHAT_PAIR_PARTNER.get(maid.getId());
    }

    /**
     * 仅当双方当前仍互为配对<b>且该配对仍归属期望的链</b>时才解除对锁（陈旧请求/迟到回调的失败路径：
     * 漂移后的新链锁不得触碰——无条件按当前配对清除会误拆第三方在途新链）。
     * 无配对对象可校验时不动任何锁（残留由 onMaidRemoved 或锁超时兜底）。
     *
     * @param expectedChainId 调用方期望的链 ID；0 表示不校验（无条件按配对解除）
     */
    static void unlockPairIfPaired(EntityMaid maid, EntityMaid peer, long nowTick, long expectedChainId) {
        if (peer == null) {
            return;
        }
        Integer maidPartner = currentPairPartner(maid, nowTick);
        Integer peerPartner = currentPairPartner(peer, nowTick);
        Long heldChain = INTER_CHAT_PAIR_CHAIN.get(maid.getId());
        if (maidPartner != null && maidPartner.equals(peer.getId())
                && peerPartner != null && peerPartner.equals(maid.getId())
                && (expectedChainId == 0 || (heldChain != null && heldChain == expectedChainId))) {
            unlockPair(maid, peer);
        }
    }

    /** 女仆死亡/卸载时清理其互聊锁（连同配对者对称释放） */
    public static void onMaidRemoved(int maidId) {
        clearPairFor(maidId);
    }

    /**
     * 清理某女仆的对锁及其配对者。
     * 幂等：校验对方仍反指本女仆，避免陈旧反向映射误删对方后来的新锁。
     */
    private static void clearPairFor(int maidId) {
        Integer partner = INTER_CHAT_PAIR_PARTNER.remove(maidId);
        INTER_CHAT_LOCK_UNTIL.remove(maidId);
        INTER_CHAT_PAIR_CHAIN.remove(maidId);
        if (partner != null && Integer.valueOf(maidId).equals(INTER_CHAT_PAIR_PARTNER.get(partner))) {
            INTER_CHAT_LOCK_UNTIL.remove(partner);
            INTER_CHAT_PAIR_PARTNER.remove(partner);
            INTER_CHAT_PAIR_CHAIN.remove(partner);
        }
    }
}
