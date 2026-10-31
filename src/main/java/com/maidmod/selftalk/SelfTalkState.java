package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Maps;
import com.maidmod.selftalk.persona.PersonaRetrievalIndex;

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

    /** 只读查询：状态条目不存在时不创建（迟到回调清理用，避免为已卸载女仆重建状态） */
    public static State peek(int maidId) {
        return STATES.get(maidId);
    }

    /**
     * 移除状态条目（女仆死亡／卸载／周期清扫的唯一出口）。
     * <p>
     * 一并退役清空世代计数器：状态条目消失后，此前捕获的在途令牌保持失效，
     * 不会因为「世代记录随状态一起没了」而被重新判为有效；状态若随后重建，
     * 拿到的是全新计数器，新请求照常有效。
     * <p>
     * 三条移除路径（离开维度、tick 死亡兜底、周期清扫）共用此处，避免状态与世代记录各清一半。
     */
    public static void remove(int maidId) {
        State state = STATES.remove(maidId);
        if (state != null) {
            // 在途规划与会话一起结束：状态条目没了，任何迟到的规划回调都不该再动游戏状态。
            // 先摘掉登记再取消，取消过程自身（或它触发的清理）不会再往已移除的状态里写登记
            state.cancelAllPlans();
            state.playerChats.retire();
            state.clearGeneration.retire();
        }
    }

    /** 全部状态条目（周期清扫用） */
    public static java.util.Set<Map.Entry<Integer, State>> entrySet() {
        return STATES.entrySet();
    }

    /**
     * 服务器停止时整体释放全部状态（同一 JVM 内再开世界不得继承上一世界的残留）。
     * <p>
     * 不直接清表，按顺序收尾每个条目：
     * <ol>
     *   <li>退役世代——此前捕获的请求令牌立即永久失效；</li>
     *   <li>对持有的自话正式回调经生命周期桥静默作废——取消上游 HTTP/排队/重试/deadline，
     *       迟到结果挡在公共响应调用点；</li>
     *   <li>逐个走 {@link #remove(int)} 移除条目并取消在途规划。</li>
     * </ol>
     * 先取快照再处理：收尾过程会修改状态表，边遍历边改会破坏 HashMap 的迭代。
     */
    public static void dropAll() {
        List<Map.Entry<Integer, State>> snapshot = new ArrayList<>(STATES.entrySet());
        for (Map.Entry<Integer, State> entry : snapshot) {
            State state = entry.getValue();
            state.clearGeneration.retire();
            SelfTalkCallback callback = state.currentSelfTalkCallback;
            if (callback != null) {
                state.currentSelfTalkCallback = null;
                AgentTweaksLifecycleBridge.cancel(callback);
            }
        }
        for (Map.Entry<Integer, State> entry : snapshot) {
            remove(entry.getKey());
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
     * <p>
     * {@code chainId} 是这次连续互聊的<b>唯一链标识</b>（自话为 0）：链上每一跳都带着它，
     * 用于「同一条链只做一次首次检索」与「过期链的续接请求直接丢弃」，
     * 不能只凭「还是同一对女仆」判断同一次会话——那对女仆可能已经开了新链。
     * <p>
     * {@code peerMessageId} 是本次需要回应的那条对方发言的<b>消息身份</b>（自话为 null）：
     * 它是档案里那条记录的稳定 UUID，用于按身份去重与按身份写入对方档案；
     * 绝不能用正文相等来判断「同一条消息」——双方重复说出相同文字属于不同消息。
     * 也不使用会在重启后重置的运行时 chainId 充当永久消息 ID。
     */
    public record DeferredRequest(
            RequestKind kind,
            EntityMaid peer,
            UUID peerMessageId,
            String peerText,
            int keep,
            double broadcastRange,
            int chainRound,
            long chainId) {
    }

    /**
     * 互聊窗口条目：一条互聊消息连同它的消息身份。
     * <p>
     * 窗口是运行时数据（重启不复活），身份来自档案记录——同一条消息在本女仆档案与
     * 窗口里是同一个 UUID，因此入站补齐与去重都按身份判定。
     */
    public record InterChatWindowEntry(UUID id, LLMMessage message) {
    }

    /**
     * 在途静默规划的登记条目（历史召回／人设召回的共用规划请求）。
     * <p>
     * 登记的目的有两个：
     * <ul>
     *   <li><b>忙判定</b>：规划期间该女仆不应再被派发自话／欢迎／互聊，否则同一次触发会重复发起请求；</li>
     *   <li><b>超时与作废</b>：规划请求可能永远不回来，超时或清空记忆时必须能<b>真正取消</b>这次规划，
     *       而不是只把忙标记清掉——否则迟到的规划结果会继续导出正式消息。</li>
     * </ul>
     * 登记与解除都在服务端主线程；解除按对象身份进行，会话结束／失败／取消只摘掉自己的那一条。
     */
    public static final class PendingPlan {
        /** 登记时的服务器 tick（与 {@code server.getTickCount()} 同基准），用于超时判定 */
        private final long startTick;
        /**
         * 取消动作：由<b>发起规划的那个会话</b>提供，即该会话自己的 cancel。
         * <p>
         * 不直接持有规划请求的取消动作：登记表是「会话在途」的登记，取消时必须让整个会话收束
         * （标记作废、结束在途回调、通知链与请求身份），只结束网络请求会留下一个仍在等待结果的会话。
         */
        private final Runnable onCancel;
        /** 是否玩家聊天路径发起：人设保存后按新配置重新准备，而不是直接丢弃玩家输入 */
        private final boolean playerChat;

        PendingPlan(long startTick, Runnable onCancel, boolean playerChat) {
            this.startTick = startTick;
            this.onCancel = onCancel;
            this.playerChat = playerChat;
        }

        public long startTick() {
            return startTick;
        }

        public boolean playerChat() {
            return playerChat;
        }

        /** 执行取消（登记表已先行摘除本条，取消过程中的回摘是无副作用的空操作） */
        void runCancel() {
            onCancel.run();
        }
    }

    public static final class State {
        /** 下次可触发自话的服务器 tick（全局单调，与 server.getTickCount() 同基准） */
        public long nextTriggerTick = 0;
        /** 是否有自话正在进行（回复未返回） */
        public boolean selfTalkPending = false;
        /**
         * 当前在途自话请求的回调身份（服务端主线程访问）。
         * 用于复位 pending 前核对「这次回调是否仍是当前请求」——被清空作废的旧回调
         * 不得清掉清空之后新请求的 pending。
         */
        public SelfTalkCallback currentSelfTalkCallback = null;
        /** selfTalkPending 置位时的服务器 tick（-1 = 未置位），用于超时强制复位防卡死 */
        public long selfTalkPendingSinceTick = -1;
        /** 玩家在途登记表：按请求身份配对，数量与最近激活 tick 统一由登记表维护。 */
        public final PlayerChatRequest.Registry playerChats = new PlayerChatRequest.Registry();
        /** 当前自话窗口内已保留的独立记录（按时间序），用于原「到阈值仅留最新一条」规则 */
        public final List<AutonomousChatRecord> windowSelfTalkMsgs = new ArrayList<>();
        /** 本女仆已欢迎过的玩家 */
        public final java.util.Set<UUID> welcomedPlayers = new java.util.HashSet<>();

        // ===== 互聊状态（与自话独立） =====
        /** 下次可触发互聊的服务器 tick */
        public long nextInterChatTriggerTick = 0;
        /** 是否有互聊正在进行（回复未返回） */
        public boolean interChatPending = false;
        /** interChatPending 置位时的服务器 tick（-1 = 未置位） */
        public long interChatPendingSinceTick = -1;
        /** 当前互聊窗口内已保留的互聊消息（按时间序，含发起与回答），携带消息身份 */
        public final List<InterChatWindowEntry> windowInterChatMsgs = new ArrayList<>();
        /** 清空世代计数器：手动清空即推进，作废清空前已开始的请求；
         * 状态条目被移除时随之一并退役，令全部在途令牌永久失效（见 {@link SelfTalkRequestToken}） */
        public final SelfTalkRequestToken.Generation clearGeneration = new SelfTalkRequestToken.Generation();
        /** 顺延队列：忙时积压的自话/互聊请求，空闲时按序派发（派发时现构建消息） */
        public final Deque<DeferredRequest> deferredRequests = new ArrayDeque<>();
        /**
         * 本女仆当前互聊请求身份（服务端主线程访问；同一时刻至多一个在途互聊请求）。
         * 用于主人插话中断时定位「即使链已释放也要作废的旧轮请求」——
         * 链释放后 {@link InterChatChain} 已无该女仆条目，只有状态表仍保留本女仆的请求引用。
         */
        public InterChatRequest currentInterChatRequest = null;

        // ===== 感知事件（附近死亡、玩家受伤、自身受伤） =====
        /**
         * 事件缓冲：按时间序保存死亡与受伤的事实记录（容量与有效期见配置）。
         * 由下一次派发的自话或互聊消费并清空（谁先派发谁消费，先派先得）；
         * 受伤采样冷却计时器随缓冲一并保存，且不因消费而重置。
         */
        public final SelfTalkEventBuffer eventBuffer = new SelfTalkEventBuffer();

        // ===== 四栏人设 =====
        /**
         * 在途静默规划（历史召回／人设召回共用的一次关键词规划）的登记表。
         * 见 {@link PendingPlan}：既是忙判定的一部分，也是超时／作废时能真正取消的抓手。
         */
        public final List<PendingPlan> pendingPlans = new ArrayList<>();
        /** 人设索引缓存对应的配置快照（null = 尚无缓存）；配置不同即重建，见 {@link MaidPersonaRuntime} */
        public MaidPersonaConfig personaIndexConfig = null;
        /** 与 {@link #personaIndexConfig} 配套的人设索引（不持久化，随状态移除释放） */
        public PersonaRetrievalIndex personaIndex = null;

        /** 该女仆当前是否有在途规划（忙判定用） */
        public boolean isPlanning() {
            return !pendingPlans.isEmpty();
        }

        /** 登记一次在途规划，返回登记条目（会话自行持据，结束／取消时按身份摘除） */
        public PendingPlan registerPlan(long startTick, Runnable onCancel, boolean playerChat) {
            PendingPlan plan = new PendingPlan(startTick, onCancel, playerChat);
            pendingPlans.add(plan);
            return plan;
        }

        /** 按对象身份摘除登记（只摘自己那条；不属于本状态或已被摘除时无副作用） */
        public void unregisterPlan(PendingPlan plan) {
            pendingPlans.remove(plan);
        }

        /**
         * 取消并摘除匹配的登记（超时、清空记忆、状态移除）。
         *
         * @param playerChat 只取消该来源的登记；{@code null} 表示全部
         * @return 实际取消的条数
         */
        public int cancelPlans(Boolean playerChat) {
            return cancelMatching(plan -> playerChat == null || plan.playerChat() == playerChat);
        }

        /**
         * 取消并摘除已超过时限的在途规划（沿用既有的在途请求超时阈值）。
         * <p>
         * 超时必须<b>真正取消</b>规划本身，而不是只清忙标记：规划请求可能永远不会回来，
         * 只清标记会让迟到的规划结果继续导出正式消息。
         *
         * @return 实际取消的条数
         */
        public int cancelExpiredPlans(long nowTick, long maxAgeTicks) {
            return cancelMatching(plan -> nowTick - plan.startTick() >= maxAgeTicks);
        }

        private int cancelMatching(java.util.function.Predicate<PendingPlan> filter) {
            List<PendingPlan> targets = new ArrayList<>();
            for (PendingPlan plan : pendingPlans) {
                if (filter.test(plan)) {
                    targets.add(plan);
                }
            }
            // 先整体摘除再执行取消：取消动作本身可能触发清理并回摘登记，重复摘除必须无副作用
            pendingPlans.removeAll(targets);
            for (PendingPlan plan : targets) {
                try {
                    plan.runCancel();
                } catch (Throwable t) {
                    MaidSelfTalkMod.LOGGER.warn("Failed to cancel a pending persona planning session", t);
                }
            }
            return targets.size();
        }

        /** 取消全部在途规划（状态移除时调用） */
        public void cancelAllPlans() {
            cancelPlans(null);
        }
    }
}
