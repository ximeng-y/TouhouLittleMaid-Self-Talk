package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.history.DialogueBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一次自话／欢迎语／互聊触发的历史组装会话：按模式决定注入哪些历史，
 * 检索模式另加静默关键词规划与纠正，最终把成品消息列表交给调用方派发。
 * <p>
 * 生命周期与「一次请求」严格对应，模式在构造时冻结——同一互聊链中每位参与者沿用各自
 * 首次发言时取得的模式，链中途切换设置不改变已开始的会话。
 * <p>
 * 环境快照由调用方传入（本次触发只采集一次），因此关键词规划、纠正与最终回复看到的是
 * 同一份环境事实，感知事件也只被消费一次。
 * <p>
 * 线程约定：{@link #start()} 与所有派发动作都在服务端主线程执行；
 * 规划回调可能在响应线程返回，因此结果一律投递回主线程再继续。
 */
public final class HistoryContextSession {

    /** 关键词规划最多纠正次数（不开放用户配置） */
    public static final int MAX_CORRECTIONS = 3;

    /** 成品消息列表的派发口：返回是否真正发出（false 表示放弃本次触发） */
    public interface Dispatcher {
        boolean dispatch(List<LLMMessage> messages);
    }

    /**
     * 本次检索的成品结果回报口（互聊链专用）。
     * <p>
     * 检索模式到达任一终态（正常召回、无可用历史、纠正耗尽、非格式失败）时调用一次，
     * <b>空结果也回报</b>——链上后续轮次据此判断「已完成首次检索」，不再重复规划。
     * 自话／欢迎语没有链，传 null。
     */
    public interface RecallReporter {
        void report(List<List<LLMMessage>> blocks);
    }

    private final EntityMaid maid;
    private final MaidAIChatManager chatManager;
    private final LLMSite site;
    private final String language;
    /** 业务提示词（硬编码指令 + 语言 + Tool 策略 + 自定义 Prompt），不含历史与上下文包装 */
    private final String basePrompt;
    private final HistoryContextMode mode;
    private final SelfTalkContexts.EnvironmentSnapshot environment;

    /** 前导 SYSTEM 段（设定 + 可选摘要），三种模式都原样保留 */
    private final List<LLMMessage> systemPrefix;
    /**
     * 历史区（三种模式共用的输入；全量模式下原样注入）。
     * <p>
     * 它<b>只含 TLM 普通玩家历史</b>：自话／欢迎语已改存独立档案，合并由
     * {@link #rebuildHistoryWithSelfTalk()} 在需要时临时拼接，检索库与「最近玩家对话」
     * 始终以这一份为输入（独立记录不进检索库）。
     */
    private final List<LLMMessage> fullHistory;
    /** 互聊窗口 + 本次需要回应的对方发言（三种模式都原样注入） */
    private final List<LLMMessage> windowMessages;
    /**
     * 本次会话说出的独立消息副本，由 {@link #rebuildHistoryWithSelfTalk()} 生成：
     * 合并视图按持久顺序号把有效自话插进历史区，来源索引随即登记，
     * 供段标签把独立记录归入自话段（按对象身份，不按正文相等）。
     */
    private final List<LLMMessage> selfTalkMessages = new ArrayList<>();
    /**
     * 本会话请求副本中独立记录的来源索引（见 {@link SelfTalkHistoryAssembler.SourceIndex}）。
     * <p>
     * 每个会话一份：合并视图与「最近一条自话」登记的都是本会话实际发出的消息对象，
     * 段标签包裹时据此按身份归段——与展示档案、有效上下文的存储对象彼此独立，
     * 不会跨请求串味。
     */
    private final SelfTalkHistoryAssembler.SourceIndex sourceIndex =
            new SelfTalkHistoryAssembler.SourceIndex();
    /** 链上已完成的首次检索结果；非 null 表示不再规划、不再跑 BM25 */
    private final CachedRecall cachedRecall;
    /** 派发许可：插话中断、请求作废等情况下返回 false 即放弃 */
    private final Supplier<Boolean> stillAllowed;
    private final Dispatcher dispatcher;
    /** 检索成品的回报口（互聊链用；自话为 null） */
    private final RecallReporter recallReporter;
    /**
     * 最终 user message 的自定义渲染器（可空）：入参是拼装完毕的提示词（业务指令 + 各类说明段），
     * 返回完整的 user message 文本。欢迎语等自带环境采集口径的调用方借此保留原有消息形态；
     * 为 null 时默认按环境快照渲染（含 {@code <context>} 包装）。
     */
    private final Function<String, String> finalMessageRenderer;

    /** 链上首次检索的成品结果（为空也是成品，不能靠空列表判断「还没算过」） */
    public record CachedRecall(boolean completed, List<List<LLMMessage>> blocks) {

        public static CachedRecall empty() {
            return new CachedRecall(true, List.of());
        }
    }

    private int corrections;
    /** 本次会话是否已经派发过正式请求（防重复派发） */
    private boolean dispatched;
    /** 本次会话是否已被作废（作废后不再产生任何派发/纠正/召回回报；幂等标记） */
    private boolean cancelled;
    /** 本次会话的检索结果是否已回报（回报口只被调用一次） */
    private boolean recallReported;

    /**
     * 可选的会话终止回调：本会话被 {@link #cancel()} 主动作废时在服务端主线程调用一次。
     * <p>
     * 互聊请求身份用它把「作废」传导给链/请求注册表（结束在途规划请求、清理注册）；
     * 自话/欢迎语调用方不传（null），会话没有链、作废即沉默，无需外部收尾。
     */
    private final Runnable onCancelled;

    /**
     * 在途规划请求的回调引用：作废/发出纠正时用于静默结束当前规划请求（迟到结果不再导出动作）。
     * 全量/精简模式没有规划请求，恒为 null；正式请求已派发后也不再持有。
     */
    private KeywordPlanCallback activePlanCallback;

    public HistoryContextSession(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                 String language, String basePrompt, HistoryContextMode mode,
                                 SelfTalkContexts.EnvironmentSnapshot environment,
                                 List<LLMMessage> systemPrefix, List<LLMMessage> fullHistory,
                                 List<LLMMessage> windowMessages, CachedRecall cachedRecall,
                                 Supplier<Boolean> stillAllowed, Dispatcher dispatcher) {
        this(maid, chatManager, site, language, basePrompt, mode, environment, systemPrefix,
                fullHistory, windowMessages, cachedRecall, stillAllowed, dispatcher, null, null, null);
    }

    public HistoryContextSession(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                 String language, String basePrompt, HistoryContextMode mode,
                                 SelfTalkContexts.EnvironmentSnapshot environment,
                                 List<LLMMessage> systemPrefix, List<LLMMessage> fullHistory,
                                 List<LLMMessage> windowMessages, CachedRecall cachedRecall,
                                 Supplier<Boolean> stillAllowed, Dispatcher dispatcher,
                                 RecallReporter recallReporter) {
        this(maid, chatManager, site, language, basePrompt, mode, environment, systemPrefix,
                fullHistory, windowMessages, cachedRecall, stillAllowed, dispatcher, recallReporter, null, null);
    }

    public HistoryContextSession(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                 String language, String basePrompt, HistoryContextMode mode,
                                 SelfTalkContexts.EnvironmentSnapshot environment,
                                 List<LLMMessage> systemPrefix, List<LLMMessage> fullHistory,
                                 List<LLMMessage> windowMessages, CachedRecall cachedRecall,
                                 Supplier<Boolean> stillAllowed, Dispatcher dispatcher,
                                 RecallReporter recallReporter,
                                 Function<String, String> finalMessageRenderer) {
        this(maid, chatManager, site, language, basePrompt, mode, environment, systemPrefix,
                fullHistory, windowMessages, cachedRecall, stillAllowed, dispatcher, recallReporter,
                finalMessageRenderer, null);
    }

    public HistoryContextSession(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                 String language, String basePrompt, HistoryContextMode mode,
                                 SelfTalkContexts.EnvironmentSnapshot environment,
                                 List<LLMMessage> systemPrefix, List<LLMMessage> fullHistory,
                                 List<LLMMessage> windowMessages, CachedRecall cachedRecall,
                                 Supplier<Boolean> stillAllowed, Dispatcher dispatcher,
                                 RecallReporter recallReporter,
                                 Function<String, String> finalMessageRenderer,
                                 Runnable onCancelled) {
        this.maid = maid;
        this.chatManager = chatManager;
        this.site = site;
        this.language = language;
        this.basePrompt = basePrompt;
        this.mode = mode;
        this.environment = environment;
        this.systemPrefix = systemPrefix;
        this.fullHistory = fullHistory;
        this.windowMessages = windowMessages;
        this.cachedRecall = cachedRecall;
        this.stillAllowed = stillAllowed;
        this.dispatcher = dispatcher;
        this.recallReporter = recallReporter;
        this.finalMessageRenderer = finalMessageRenderer;
        this.onCancelled = onCancelled;
    }

    /**
     * 会话被作废（业务判定本请求不再期待任何结果）时调用：终止在途规划请求并触发外部收尾。
     * <p>
     * 不在此置 dispatched／改 dispatched 之外的状态：迟到结果仍可能进入 {@link #deliver} 系列，
     * 但已被别的入口（如请求身份的停止标记、链中断）判过许可而沉默；本方法只负责把「规划请求
     * 仍在飞行」这件事收束掉——让规划回调的迟到结果不再导出下一步动作。
     * 幂等：第二次调用不再重复终止。
     */
    void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        if (activePlanCallback != null) {
            activePlanCallback.completeSilently();
            activePlanCallback = null;
        }
        if (onCancelled != null) {
            onCancelled.run();
        }
    }

    /** 本次会话的模式（供调用方记录进互聊链上下文） */
    public HistoryContextMode mode() {
        return mode;
    }

    /**
     * 开始本次会话。
     * <p>
     * 全量模式与精简模式都是同步完成；检索模式在需要规划时先发一次静默规划请求，
     * 索引重建时先让出主线程算索引，再由回调驱动后续纠正或正式生成。
     * <p>
     * 互聊链上下文的「继续与否」判据是本次会话的<b>成品结果是否真正交付</b>（见 {@link Dispatcher}），
     * 因此插话中断后的迟到结果不会被链误认成「尚未结束」。
     */
    public void start() {
        switch (mode) {
            case FULL -> dispatchFull();
            case COMPACT -> dispatchReduced(null, false);
            case RETRIEVAL -> startRetrieval();
        }
    }

    // ===== 全量模式 =====

    /**
     * 全量：保留现有历史组装行为，不发规划请求、不建索引、不加新的说明段。
     * <p>
     * 全量模式不观测召回，链进入交付等待（不是「等不到」）——否则同链下一轮会在
     * 前一跳尚未交付时就派发，破坏串行语义。
     */
    private void dispatchFull() {
        List<LLMMessage> history = rebuildHistoryWithSelfTalk();
        List<LLMMessage> messages = new ArrayList<>(systemPrefix);
        messages.addAll(history);
        messages.addAll(windowMessages);
        String prompt = basePrompt + SelfTalkContexts.latestSelfTalkNote(language, !selfTalkMessages.isEmpty())
                + SelfTalkContexts.recalledHistoryNote(language, false);
        messages.add(LLMMessage.userChat(maid, renderFinalUserMessage(prompt)));
        int historyCount = systemPrefix.size() + history.size();
        SelfTalkContexts.wrapSegments(maid, messages, historyCount, windowMessages.size(), sourceIndex);
        deliver(messages);
    }

    // ===== 精简模式 =====

    /**
     * 精简：不注入玩家聊天原文，只保留已有摘要（在 systemPrefix 内）、
     * 最近一条可识别自话与当前互聊窗口。
     *
     * @param recalledBlocks 检索模式的召回块；精简模式传 null
     * @param recallNote     是否追加「召回片段是历史资料」的说明（仅检索模式）
     */
    private void dispatchReduced(List<LLMMessage> recalledBlocks, boolean recallNote) {
        // 合并视图先建：最新一条自话与它取自同一条记录，两条路径的段标签判据一致
        rebuildHistoryWithSelfTalk();
        LLMMessage latestSelfTalk = latestSelfTalkMessage();
        List<LLMMessage> messages = new ArrayList<>(systemPrefix);
        if (recalledBlocks != null) {
            messages.addAll(recalledBlocks);
        }
        if (latestSelfTalk != null) {
            messages.add(latestSelfTalk);
        }
        // 段标签的历史区游标：前导 SYSTEM + 召回块 + 最近一条自话（不含其后的窗口与本轮消息）
        int historyCount = messages.size();
        messages.addAll(windowMessages);
        String prompt = basePrompt
                + SelfTalkContexts.latestSelfTalkNote(language, latestSelfTalk != null)
                + SelfTalkContexts.recalledHistoryNote(language,
                recallNote && recalledBlocks != null && !recalledBlocks.isEmpty());
        messages.add(LLMMessage.userChat(maid, renderFinalUserMessage(prompt)));
        SelfTalkContexts.wrapSegments(maid, messages, historyCount, windowMessages.size(), sourceIndex);
        deliver(messages);
    }

    /** 渲染本轮最终 user message：默认按环境快照渲染；注入了自定义渲染器（如欢迎语）时走调用方口径 */
    private String renderFinalUserMessage(String prompt) {
        if (finalMessageRenderer != null) {
            return finalMessageRenderer.apply(prompt);
        }
        return SelfTalkContexts.renderConfiguredUserMessage(prompt, language, environment);
    }

    /**
     * 把有效自话上下文按持久顺序号并进历史区，返回本次请求使用的合并历史副本。
     * <p>
     * 每次组装前重新生成：合并时登记的消息对象就是随后交给请求的那一批，
     * 段标签据此按<b>对象身份</b>把独立记录归入自话段（清洗只增删元素、不重建对象）。
     * {@link #fullHistory} 始终保持只含 TLM 玩家历史，检索库与「最近玩家对话」不受影响。
     */
    private List<LLMMessage> rebuildHistoryWithSelfTalk() {
        List<LLMMessage> merged = SelfTalkHistoryAssembler.mergeValidSelfTalk(maid, fullHistory, sourceIndex);
        if (merged != fullHistory) {
            selfTalkMessages.clear();
            for (LLMMessage message : merged) {
                if (sourceIndex.sourceOf(message) != null) {
                    selfTalkMessages.add(message);
                }
            }
        }
        return merged;
    }

    /**
     * 最近一条自话／欢迎语（记入模型上下文的原消息）。
     * <p>
     * 只读<b>有效自话快照</b>：不扫描 TLM 历史、不扫描展示档案——自动遗忘掉的旧自话已不在有效上下文里，
     * 不能在精简模式下复活；展示档案被容量淘汰也不影响仍在有效上下文里的记录。
     * <p>
     * 与合并视图插进历史区的是<b>同一条记录</b>，因此精简模式下这段上下文同样带自话段标签。
     */
    private LLMMessage latestSelfTalkMessage() {
        AutonomousChatHistory archive = AutonomousChatHistoryHost.of(maid);
        if (archive == null) {
            return null;
        }
        AutonomousChatRecord record = archive.latestValidSelfTalk();
        if (record == null || record.message() == null || record.message().isBlank()) {
            return null;
        }
        return SelfTalkHistoryAssembler.asRequestMessage(record, sourceIndex);
    }

    // ===== 检索模式 =====

    private void startRetrieval() {
        // 链上已完成首次检索（含「结果为空」）：直接复用，不再规划、不再跑 BM25
        if (cachedRecall != null && cachedRecall.completed()) {
            dispatchReduced(SelfTalkHistoryAssembler.flattenBlocks(
                    SelfTalkHistoryAssembler.retainAliveBlocks(cachedRecall.blocks(), fullHistory)), true);
            return;
        }
        HistoryRetrievalCache.indexFor(maid, fullHistory, result -> {
            // 索引可能是在文本执行器线程算完再回主线程的；本回调统一在主线程执行
            if (!allowed()) {
                return;
            }
            if (!result.hasSearchableHistory()) {
                // 没有可检索历史：直接正式生成，不发送规划请求。
                // 回报「已完成、结果为空」——同链后续轮次不再重复尝试
                reportRecall(List.of());
                dispatchReduced(List.of(), false);
                return;
            }
            sendPlanRequest(planningMessages(null, null), result);
        });
    }

    /**
     * 规划请求的消息列表：独立系统提示 + 一份「资料」用户消息。
     * <p>
     * 刻意<b>不</b>复用正式请求的整段 Prompt——那里含「正式台词输出格式」「Tool 策略」等业务指令，
     * 会与「只输出一个 JSON 对象」的要求竞争。
     *
     * @param previousOutput 上次的错误输出（纠正时非 null）
     * @param errorText      简短具体的错误说明（纠正时非 null）
     */
    private List<LLMMessage> planningMessages(String previousOutput, String errorText) {
        List<LLMMessage> messages = new ArrayList<>(2);
        messages.add(LLMMessage.systemChat(maid, SelfTalkPrompts.KEYWORD_PLAN_SYSTEM));
        messages.add(LLMMessage.userChat(maid,
                SelfTalkPrompts.buildKeywordPlanInput(language, systemPrefix, fullHistory,
                        planningRecentDialogue(), latestSelfTalkMessage(), windowMessages,
                        environment, previousOutput, errorText)));
        return messages;
    }

    /** 最近一轮可检索玩家对话（规划资料用；不因用于规划就自动进入最终请求） */
    private List<LLMMessage> planningRecentDialogue() {
        var searchable = SelfTalkHistoryAssembler.filterSearchable(
                SelfTalkHistoryAssembler.toSearchableView(fullHistory),
                SelfTalkHistoryAssembler.selfTalkFingerprints(maid));
        if (searchable.isEmpty()) {
            return List.of();
        }
        List<DialogueBlock> blocks = com.maidmod.selftalk.history.PlayerDialogueLibrary
                .buildBlocks(searchable.searchable());
        if (blocks.isEmpty()) {
            return List.of();
        }
        DialogueBlock last = blocks.get(blocks.size() - 1);
        List<LLMMessage> recent = new ArrayList<>();
        for (int i = last.startIndex(); i < last.endIndex(); i++) {
            int historyIndex = searchable.historyIndexAt(i);
            if (historyIndex >= 0) {
                recent.add(fullHistory.get(historyIndex));
            }
        }
        return List.copyOf(recent);
    }

    private void sendPlanRequest(List<LLMMessage> messages, HistoryRetrievalCache.Result result) {
        if (!allowed()) {
            return;
        }
        // 规划与纠正共用同一次在途身份：不重复经过全局发起限流，也不重设触发冷却
        LLMClient client = site.client();
        try {
            KeywordPlanCallback planCallback = new KeywordPlanCallback(chatManager, messages, outcome ->
                    onServerThread(() -> onPlanOutcome(outcome, result)));
            activePlanCallback = planCallback;
            client.chat(planCallback);
        } catch (Throwable t) {
            activePlanCallback = null;
            // 传输阶段就抛异常（site.url 非法等）：本次不召回，仍进入一次正式生成
            MaidSelfTalkMod.LOGGER.warn("Failed to dispatch keyword plan for maid {}, continue without recall",
                    maid.getId(), t);
            // 与其他失败出口一致：同步抛出同样回报「首次检索完成、结果为空」，
            // 否则互聊链上本女仆的后续轮次会重复规划
            reportRecall(List.of());
            dispatchReduced(List.of(), false);
        }
    }

    /**
     * 规划结果处理。
     * <p>
     * 三条出口互斥：
     * <ul>
     *   <li>传输类失败（{@code errorText == null}）：本次不召回，进入一次正式生成，不消耗纠正预算；</li>
     *   <li>格式错误且预算未耗尽：再发一次纠正请求（保留同一份规划基础输入，只附上次错误）；</li>
     *   <li>成功：跑 BM25 召回，随后正式生成。</li>
     * </ul>
     * 第三次纠正仍失败时，本次召回结果为空，继续基础上下文的正式回复——
     * 不回退全量、不编造关键词、不调用其他「修复模型」。
     */
    private void onPlanOutcome(KeywordPlanCallback.Outcome outcome, HistoryRetrievalCache.Result result) {
        // 当前规划回调已收敛（正常成功/失败/格式错误都到这里），解除在途跟踪；
        // 后续纠正请求由 sendPlanRequest 重新登记，作废时只对真正在途的规划请求收束
        activePlanCallback = null;
        if (!allowed()) {
            return;
        }
        if (outcome.ok()) {
            List<List<LLMMessage>> blocks = retrieve(outcome.keywords(), result);
            reportRecall(blocks);
            dispatchReduced(SelfTalkHistoryAssembler.flattenBlocks(blocks), true);
            return;
        }        if (outcome.errorText() == null) {
            // 非格式错误（网络/HTTP/认证/限流/响应信封）：不用纠正预算盲目重试
            reportRecall(List.of());
            dispatchReduced(List.of(), false);
            return;
        }
        if (corrections >= MAX_CORRECTIONS) {
            MaidSelfTalkMod.LOGGER.warn("Keyword plan failed after {} corrections for maid {}, no recall",
                    MAX_CORRECTIONS, maid.getId());
            reportRecall(List.of());
            dispatchReduced(List.of(), false);
            return;
        }
        corrections++;
        // 保留同一份规划基础输入，只附上上一次的错误输出与简短说明（不累积全部错误历史）
        sendPlanRequest(planningMessages(outcome.rawOutput(), outcome.errorText()), result);
    }

    /** 把召回结果按块回报给链（同一女仆同一链只生效一次；空结果同样回报） */
    private void reportRecall(List<List<LLMMessage>> blocks) {
        if (recallReporter == null || recallReported) {
            return;
        }
        recallReported = true;
        recallReporter.report(blocks);
    }

    /** 按关键词跑 BM25，并把命中块映射回原始历史区消息（按块切分，顺序同历史） */
    private List<List<LLMMessage>> retrieve(List<String> keywords, HistoryRetrievalCache.Result result) {
        List<DialogueBlock> blocks = result.index().search(keywords);
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<List<LLMMessage>> recalled = new ArrayList<>(blocks.size());
        for (DialogueBlock block : blocks) {
            List<LLMMessage> messages = SelfTalkHistoryAssembler.selectRecalledMessages(
                    fullHistory, result.searchable(), List.of(block));
            if (!messages.isEmpty()) {
                recalled.add(messages);
            }
        }
        return List.copyOf(recalled);
    }

    // ===== 派发 =====

    private boolean allowed() {
        return !dispatched && !cancelled && maid.isAlive() && (stillAllowed == null || stillAllowed.get());
    }

    /** 正式派发（服务端主线程）：派发前再复核一次许可，超时或插话中断的迟到结果不会继续发请求 */
    private void deliver(List<LLMMessage> messages) {
        if (!allowed()) {
            settle(false);
            return;
        }
        dispatched = true;
        if (dispatcher.dispatch(messages)) {
            settle(true);
        } else {
            MaidSelfTalkMod.LOGGER.warn("Failed to dispatch history-mode request for maid {}", maid.getId());
            settle(false);
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

    /** 派发失败（含被中断／请求作废）时回报空召回：失败同样算「首次检索完成」，链不得继续等待 */
    private void settle(boolean delivered) {
        if (!delivered) {
            reportRecall(List.of());
        }
    }
}
