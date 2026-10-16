package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.List;
import java.util.function.Supplier;

/**
 * 共用的静默关键词规划会话：玩家聊天路径与自动聊天路径都走这一份实现，
 * 不维护两套规划协议。
 * <p>
 * 一次会话负责：发出规划请求、格式纠错（最多 {@link #MAX_CORRECTIONS} 次）、
 * 取消与迟到结果处理，以及把两组关键词交回调用方。历史召回与人设召回<b>共用同一次</b>规划，
 * 由调用方分别用两组关键词去检索各自的索引。
 * <p>
 * 出口只有两个，且必定走其中一个（幂等，只走一次）：
 * <ul>
 *   <li>{@link #cancel()}：静默收尾，<b>不</b>回调消费者——调用方按自己的取消口收尾，
 *       迟到结果不再导出任何动作；</li>
 *   <li>{@link Consumer#accept}：正常终局（含网络失败/同步异常/纠正耗尽）。此时结果
 *       按目标开关过滤：未启用的目标恒为空，不靠模型守规矩。</li>
 * </ul>
 * 规划请求沿用静默、禁工具、不计入正式聊天 token 缓存的 {@link KeywordPlanCallback}；
 * 请求在途期间登记进 {@link SelfTalkState.State#pendingPlans}，使忙判定把女仆视为占用，
 * 并让超时与清空记忆能<b>真正取消</b>这次规划（只清忙标记会让迟到的规划结果继续导出正式消息）。
 * <p>
 * 线程约定：{@link #start()}、{@link #cancel()} 与消费者回调都在服务端主线程执行；
 * 规划回调可能在响应线程返回，结果一律投递回主线程再继续。
 */
public final class KeywordPlanningSession {

    /** 格式错误最多纠正次数（不开放用户配置） */
    public static final int MAX_CORRECTIONS = 3;

    /** 规划成品：两组关键词已按目标开关过滤（未启用的目标恒为空列表） */
    public record Plan(List<String> historyKeywords, List<String> personaKeywords) {

        public static final Plan EMPTY = new Plan(List.of(), List.of());

        public Plan {
            historyKeywords = historyKeywords == null ? List.of() : List.copyOf(historyKeywords);
            personaKeywords = personaKeywords == null ? List.of() : List.copyOf(personaKeywords);
        }
    }

    /** 结果消费者：终局时在服务端主线程调用一次 */
    @FunctionalInterface
    public interface Consumer {
        void accept(Plan plan);
    }

    /**
     * 规划消息构造口：每次尝试（首轮与纠正）现构建一份。
     * 纠正时只带最近一次的错误输出与简短说明，不累积全部错误历史。
     */
    @FunctionalInterface
    public interface PlanningMessages {
        List<LLMMessage> build(String previousOutput, String errorText);
    }

    private final EntityMaid maid;
    private final MaidAIChatManager chatManager;
    private final LLMSite site;
    private final boolean historyEnabled;
    private final boolean personaEnabled;
    private final PlanningMessages planningMessages;
    private final Supplier<Boolean> stillAllowed;
    private final Consumer consumer;
    private final Runnable onCancelled;
    private final boolean playerChat;

    /** 在途规划的回调引用：取消时静默结束它（迟到结果不再导出动作） */
    private KeywordPlanCallback activeCallback;
    /** 本次规划在状态表中的登记（结束或取消时按对象身份摘除） */
    private SelfTalkState.PendingPlan registration;
    private int corrections;
    private boolean finished;
    private boolean cancelled;

    /**
     * @param onCancelled 静默取消时的收尾动作（可空）：调用方借它把自己持有的会话登记一并清掉，
     *                    否则被取消的会话会永远留在「待恢复」名单里
     */
    public KeywordPlanningSession(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                  boolean historyEnabled, boolean personaEnabled,
                                  PlanningMessages planningMessages, Supplier<Boolean> stillAllowed,
                                  Consumer consumer, Runnable onCancelled, boolean playerChat) {
        this.maid = maid;
        this.chatManager = chatManager;
        this.site = site;
        this.historyEnabled = historyEnabled;
        this.personaEnabled = personaEnabled;
        this.planningMessages = planningMessages;
        this.stillAllowed = stillAllowed;
        this.consumer = consumer;
        this.onCancelled = onCancelled;
        this.playerChat = playerChat;
    }

    /**
     * 登记在途规划并发出首次请求（服务端主线程）。
     * <p>
     * 请求未成功发出（同步异常）时同样以空结果收束，调用方据此继续正式聊天。
     */
    public void start() {
        if (!allowed()) {
            // 已无许可：静默收尾，不回调消费者（调用方自己的取消口负责后续）
            cancel();
            return;
        }
        long nowTick = maid.level().getServer() == null ? 0L : maid.level().getServer().getTickCount();
        registration = SelfTalkState.get(maid.getId()).registerPlan(nowTick, this::cancel, playerChat);
        send(null, null);
    }

    /**
     * 静默取消：结束在途规划请求、摘除登记，不再纠正、不回调消费者。
     * <p>
     * 幂等；登记表（超时、清空记忆、人设变更、状态移除）也经此收尾整个会话。
     */
    public void cancel() {
        if (cancelled || finished) {
            return;
        }
        cancelled = true;
        if (activeCallback != null) {
            activeCallback.completeSilently();
            activeCallback = null;
        }
        unregister();
        if (onCancelled != null) {
            onCancelled.run();
        }
    }

    /** 本次规划是否已进入终局（正常结束或取消） */
    public boolean isFinished() {
        return finished || cancelled;
    }

    private void send(String previousOutput, String errorText) {
        if (!allowed()) {
            finish(Plan.EMPTY);
            return;
        }
        List<LLMMessage> messages = planningMessages.build(previousOutput, errorText);
        try {
            LLMClient client = site.client();
            KeywordPlanCallback callback = new KeywordPlanCallback(chatManager, messages,
                    outcome -> onServerThread(() -> onOutcome(outcome)));
            activeCallback = callback;
            client.chat(callback);
        } catch (Throwable t) {
            activeCallback = null;
            // 传输阶段就抛异常（site.url 非法等）：本次不召回，调用方仍进入一次正式生成
            MaidSelfTalkMod.LOGGER.warn("Failed to dispatch keyword plan for maid {}, continue without recall",
                    maid.getId(), t);
            finish(Plan.EMPTY);
        }
    }

    private void onOutcome(KeywordPlanCallback.Outcome outcome) {
        // 当前规划回调已收敛（成功/失败/格式错误都到这里），解除在途跟踪；
        // 后续纠正请求由 send 重新登记，取消时只对真正在途的那次请求收束
        activeCallback = null;
        if (finished || cancelled) {
            return;
        }
        if (!allowed()) {
            // 许可已收回（插话中断、请求作废、清空世代失效）：静默收尾并摘除登记，
            // 不留着「永远是忙」的残留登记挡住该女仆后续触发
            cancel();
            return;
        }
        if (outcome.ok()) {
            finish(filter(outcome));
            return;
        }
        if (outcome.errorText() == null) {
            // 非格式错误（网络/HTTP/认证/限流/响应信封）：不用纠正预算盲目重试
            finish(Plan.EMPTY);
            return;
        }
        if (corrections >= MAX_CORRECTIONS) {
            MaidSelfTalkMod.LOGGER.warn("Keyword plan failed after {} corrections for maid {}, no recall",
                    MAX_CORRECTIONS, maid.getId());
            finish(Plan.EMPTY);
            return;
        }
        corrections++;
        send(outcome.rawOutput(), outcome.errorText());
    }

    /**
     * 按目标开关过滤关键词：未启用的目标即使模型多给了关键词也强制忽略。
     * <p>
     * 提示词里已明确要求未启用的目标返回空数组，此处是消费侧的独立保证——
     * 模型不守规矩时不能把不该发的内容带进检索。
     */
    private Plan filter(KeywordPlanCallback.Outcome outcome) {
        return new Plan(historyEnabled ? outcome.historyKeywords() : List.of(),
                personaEnabled ? outcome.personaKeywords() : List.of());
    }

    private void finish(Plan plan) {
        if (finished || cancelled) {
            return;
        }
        finished = true;
        unregister();
        consumer.accept(plan);
    }

    private boolean allowed() {
        if (finished || cancelled || !maid.isAlive()) {
            return false;
        }
        return stillAllowed == null || stillAllowed.get();
    }

    private void unregister() {
        SelfTalkState.PendingPlan plan = registration;
        registration = null;
        if (plan == null) {
            return;
        }
        // 状态可能已随女仆移除而消失：按身份摘除失败即为已作废，不再回写
        SelfTalkState.State state = SelfTalkState.peek(maid.getId());
        if (state != null) {
            state.unregisterPlan(plan);
        }
    }

    /** 把响应线程的结果投递回服务端主线程 */
    private void onServerThread(Runnable runnable) {
        if (maid.level().getServer() == null || maid.level().getServer().isSameThread()) {
            runnable.run();
        } else {
            maid.level().getServer().execute(runnable);
        }
    }
}
