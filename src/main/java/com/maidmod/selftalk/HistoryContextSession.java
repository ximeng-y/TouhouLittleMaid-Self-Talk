package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.history.DialogueBlock;
import com.maidmod.selftalk.persona.PersonaFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 一次自话／欢迎语／互聊触发的历史与人设组装会话：按模式决定注入哪些历史，
 * 需要召回时另加一次静默关键词规划与纠正，最终把成品消息列表交给调用方派发。
 * <p>
 * 两个召回目标互相独立：
 * <ul>
 *   <li><b>历史目标</b>：历史模式为 RETRIEVAL，且存在需要检索的历史；</li>
 *   <li><b>人设目标</b>：四栏模式、动态人设非空，且链上没有本配置的召回结果。</li>
 * </ul>
 * 任一目标需要规划即调用一次共用规划（{@link KeywordPlanningSession}），两组关键词随后分别
 * 检索各自的索引；组装始终按<b>原历史模式</b>进行——FULL 保留全量历史、COMPACT 保留现有精简规则，
 * 绝不因为启用了人设召回就变成历史检索模式。
 * <p>
 * 生命周期与「一次请求」严格对应，模式与人设配置在构造时冻结——同一互聊链中每位参与者沿用各自
 * 首次发言时取得的快照，链中途切换设置不改变已开始的会话。
 * <p>
 * 环境快照由调用方传入（本次触发只采集一次），因此关键词规划、纠正与最终回复看到的是
 * 同一份环境事实，感知事件也只被消费一次。
 * <p>
 * 线程约定：{@link #start()} 与所有派发动作都在服务端主线程执行；
 * 规划回调可能在响应线程返回，因此结果一律投递回主线程再继续。
 */
public final class HistoryContextSession {

    /** 成品消息列表的派发口：返回是否真正发出（false 表示放弃本次触发） */
    public interface Dispatcher {
        boolean dispatch(PreparedRequest request);
    }

    /**
     * 交给派发口的成品请求：实际消息列表、本次冻结的历史模式与「是否有实际偷听新增／重新召回」标志。
     * <p>
     * 该标志来自实际注入编号相对有效集合的增量，不扫描 XML、摘要或旧历史推断。
     */
    public record PreparedRequest(List<LLMMessage> messages, HistoryContextMode mode, boolean overheardEvent) {
    }

    /**
     * start() 的受理结果：区分「同步派发成功」「同步派发失败」与「异步流程已受理」。
     * 调用方据此决定是否把本次触发记为已完成（欢迎语标记、顺延出队等）。
     */
    public enum StartOutcome {
        /** 同步派发成功：正式请求已真实发出 */
        DISPATCHED,
        /** 异步流程已受理：检索模式的建索引/规划仍在进行，正式派发稍后发生，结果不回传本次调用 */
        ACCEPTED_ASYNC,
        /** 同步派发失败：正式请求未发出且本次会话同步终止，调用方可按既有规则安全重试 */
        FAILED
    }

    /**
     * 本次召回结果的回报口（互聊链专用）。
     * <p>
     * 历史与人设<b>分别</b>回报，且各自到达任一终态时调用一次，<b>空结果也回报</b>——
     * 链上后续轮次据此判断「本链该目标已完成首次召回」，不再重复规划。
     * 两者的可用性独立判断：不能因为历史无需召回就跳过人设回报（或反之）。
     * 自话／欢迎语没有链，传 null。
     */
    public interface RecallReporter {
        void reportHistory(List<List<LLMMessage>> blocks);

        void reportPersona(MaidPersonaConfig config, List<PersonaFragment> fragments);

        /** 首次正式派发时回报本轮实际注入的偷听资料编号（链内后续轮次复用；空表也回报） */
        void reportOverheardRound(List<Long> ids);
    }

    /**
     * 最终 user message 的渲染口（可空）：入参是拼装完毕的提示词（业务指令 + 各类说明段）
     * 与本轮的人设召回块（可能为空串），返回完整的 user message 文本。
     * 欢迎语等自带环境采集口径的调用方借此保留原有消息形态；为 null 时默认按环境快照渲染
     * （含 {@code <context>} 包装，人设块插在当前情境与感知背景之间）。
     */
    public interface FinalMessageRenderer {
        String render(String prompt, String personaBlock, String overheardBlock);
    }

    /** 链上首次历史检索的成品结果（为空也是成品，不能靠空列表判断「还没算过」） */
    public record CachedRecall(boolean completed, List<List<LLMMessage>> blocks) {

        public static CachedRecall empty() {
            return new CachedRecall(true, List.of());
        }
    }

    /**
     * 链上已完成的人设召回结果。
     * <p>
     * 携带取得该结果时的<b>完整配置快照</b>：配置不同（含模式切换、四栏内容改动）即视为无缓存，
     * 必须重新召回，旧结果不得复用。
     */
    public record CachedPersonaRecall(boolean completed, MaidPersonaConfig config,
                                      List<PersonaFragment> fragments) {
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
    /** 链上已完成的首次历史检索结果；非 null 表示不再规划历史、不再跑历史 BM25 */
    private final CachedRecall cachedRecall;
    /** 链上已完成的人设召回结果；配置快照不一致时视为无缓存 */
    private final CachedPersonaRecall cachedPersonaRecall;
    /** 本次会话冻结的人设配置快照（模式 + 四栏） */
    private final MaidPersonaConfig personaConfig;
    /** 本次请求的偷听资料会话（在登记准备期之后创建，快照随本会话释放） */
    private OverheardRecallSession overheard;
    /** 本次是否命中近期召回概率（欢迎语恒为 false；互聊由整条链的判定传入） */
    private final boolean overheardRecentHit;
    /** 同链复用：首次正式派发时记录的本轮资料编号（空表表示不复用） */
    private final List<Long> reusedOverheardIds;
    /** 本轮触发内容（写进规划资料，供模型判断该查什么；可为 null） */
    private final String triggerText;
    /** 派发许可：插话中断、请求作废等情况下返回 false 即放弃 */
    private final Supplier<Boolean> stillAllowed;
    private final Dispatcher dispatcher;
    /** 召回成品的回报口（互聊链用；自话为 null） */
    private final RecallReporter recallReporter;
    /** 最终 user message 的自定义渲染器（可空），见 {@link FinalMessageRenderer} */
    private final FinalMessageRenderer finalMessageRenderer;

    /**
     * 可选的会话终止回调：本会话被 {@link #cancel()} 主动作废时在服务端主线程调用一次。
     * <p>
     * 互聊请求身份用它把「作废」传导给链/请求注册表（结束在途规划请求、清理注册）；
     * 自话/欢迎语调用方不传（null），会话没有链、作废即沉默，无需外部收尾。
     */
    private final Runnable onCancelled;

    /** 在途的共用规划会话（无规划时 null；作废时一并静默收尾） */
    private KeywordPlanningSession planning;
    /** 本次规划的目标开关（供规划消息构造使用，与规划会话同源） */
    private boolean planningHistoryEnabled;
    private boolean planningPersonaEnabled;

    /** 本次会话是否已经派发过正式请求（防重复派发） */
    private boolean dispatched;
    /** 本次会话是否已被作废（作废后不再产生任何派发/纠正/召回回报；幂等标记） */
    private boolean cancelled;
    /**
     * 本会话在状态表中的自动准备登记所属的状态（null = 未登记或已摘除）。
     * <p>
     * 保存实际登记的那个 {@link SelfTalkState.State} 引用而不是每次按女仆 ID 现查：
     * 摘除不得创建状态，也不能误碰女仆后来重建的新状态。
     */
    private SelfTalkState.State preparationOwner;
    /** 本次会话的历史检索结果是否已回报（回报口只被调用一次） */
    private boolean historyReported;
    /** 本次会话的偷听本轮资料是否已回报（回报口只被调用一次） */
    private boolean overheardReported;
    /**
     * 同步路径的收束结果：{@link #start()} 期间内联完成（缓存命中、无历史可检索、
     * 同步发送失败）时置位，由 start 原样回传；异步路径保持 null。
     */
    private StartOutcome syncOutcome;

    public HistoryContextSession(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                 String language, String basePrompt, HistoryContextMode mode,
                                 SelfTalkContexts.EnvironmentSnapshot environment,
                                 List<LLMMessage> systemPrefix, List<LLMMessage> fullHistory,
                                 List<LLMMessage> windowMessages, CachedRecall cachedRecall,
                                 CachedPersonaRecall cachedPersonaRecall, MaidPersonaConfig personaConfig,
                                 boolean overheardRecentHit, List<Long> reusedOverheardIds,
                                 String triggerText, Supplier<Boolean> stillAllowed, Dispatcher dispatcher,
                                 RecallReporter recallReporter, FinalMessageRenderer finalMessageRenderer,
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
        this.cachedPersonaRecall = cachedPersonaRecall;
        this.personaConfig = personaConfig == null ? MaidPersonaConfig.DEFAULT : personaConfig;
        this.overheardRecentHit = overheardRecentHit;
        this.reusedOverheardIds = reusedOverheardIds == null ? List.of() : List.copyOf(reusedOverheardIds);
        this.overheard = OverheardRecallSession.inactive(maid, mode);
        this.triggerText = triggerText;
        this.stillAllowed = stillAllowed;
        this.dispatcher = dispatcher;
        this.recallReporter = recallReporter;
        this.finalMessageRenderer = finalMessageRenderer;
        this.onCancelled = onCancelled;
    }

    /**
     * 会话被作废（业务判定本请求不再期待任何结果）时调用：静默结束在途规划并触发外部收尾。
     * <p>
     * 规划会话的取消不回调消费者，因此作废后不会再有正式派发、纠正或召回回报；
     * 迟到结果由规划回调自身的作废标记挡下。幂等：第二次调用不再重复终止。
     * <p>
     * 作废标记持久保留：关闭总开关作废的会话即使随后重新开启也不会复活——准备链条上
     * 每一处许可判断都先看这个标记。
     */
    void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        releasePreparation();
        // 偷听资料只建立短命 pin：取消、失败与提交后都必须幂等释放，不提前写记忆
        overheard.close();
        if (planning != null) {
            planning.cancel();
            planning = null;
        }
        if (onCancelled != null) {
            onCancelled.run();
        }
    }

    /**
     * 登记本会话为「自动回答准备期」（服务端主线程，在任何同步/异步准备动作之前）。
     * <p>
     * 覆盖索引构建、关键词规划、纠正与资料选择整段：这些阶段里都还没有正式请求，
     * 关闭总开关或状态移除时必须能整体作废。
     */
    private void registerPreparation() {
        SelfTalkState.State state = SelfTalkState.peek(maid.getId());
        if (state == null) {
            return;
        }
        preparationOwner = state;
        state.registerAutomaticPreparation(this);
    }

    /**
     * 摘除准备登记（幂等）。
     * <p>
     * 正式生成进入派发后调用：此后本会话不再属于「待生成准备」，关闭总开关不能借它
     * 取消当前正式回答。摘除不创建状态、也不影响同一女仆后来创建的新状态。
     */
    private void releasePreparation() {
        SelfTalkState.State owner = preparationOwner;
        preparationOwner = null;
        if (owner != null) {
            owner.unregisterAutomaticPreparation(this);
        }
    }

    /** 本次会话的模式（供调用方记录进互聊链上下文） */
    public HistoryContextMode mode() {
        return mode;
    }

    /**
     * 开始本次会话。
     * <p>
     * 没有召回目标时同步完成（全量/精简模式的常规路径，以及链上已缓存全部结果的检索模式）；
     * 需要规划时先发一次静默规划请求，再由回调驱动纠正或正式生成。
     * <p>
     * 互聊链上下文的「继续与否」判据是本次会话的<b>成品结果是否真正交付</b>（见 {@link Dispatcher}），
     * 因此插话中断后的迟到结果不会被链误认成「尚未结束」。
     *
     * @return 受理结果：同步派发成功／异步流程已受理／同步派发失败，见 {@link StartOutcome}
     */
    public StartOutcome start() {
        // 登记之前先复核许可：没有许可就不启动任何准备（也不留下准备登记）
        if (!allowed()) {
            cancel();
            return StartOutcome.FAILED;
        }
        // 登记必须在任何同步或异步准备动作之前：索引构建、关键词规划、纠正与资料选择
        // 都在这之后发生，关闭总开关时才能整体作废这一段准备期
        registerPreparation();
        // 偷听资料与准备登记同一时点冻结：此后异步索引、规划与纠正都复用这份快照，
        // 不重新抽概率、不读取新锚点。同链复用只持有编号，按当前绑定重新读取
        this.overheard = reusedOverheardIds.isEmpty()
                ? OverheardRecallSession.prepare(maid, overheardRecentHit, mode)
                : OverheardRecallSession.reuse(maid, mode, reusedOverheardIds);
        switch (mode) {
            case FULL, COMPACT -> {
                if (needsPersonaRecall()) {
                    // 全量／精简模式同样支持人设召回：只发规划，历史组装仍按原模式进行
                    startPlanning(false, null);
                } else {
                    dispatchAssembled(List.of(), cachedPersonaFragments(), false);
                }
            }
            case RETRIEVAL -> startRetrieval();
        }
        return syncOutcome != null ? syncOutcome : StartOutcome.ACCEPTED_ASYNC;
    }

    // ===== 召回目标的判定 =====

    /**
     * 本轮是否需要为人设发起规划：四栏模式且动态人设非空，且链上没有本配置的召回结果。
     * <p>
     * 「链上已完成（含空结果）+ 配置快照一致」才算有效缓存；人设一改，旧结果立即失效。
     */
    private boolean needsPersonaRecall() {
        if (cachedPersonaRecall != null && cachedPersonaRecall.completed()
                && personaConfig.equals(cachedPersonaRecall.config())) {
            return false;
        }
        return MaidPersonaRuntime.hasDynamicPersona(personaConfig);
    }

    /** 链上可复用的人设召回片段（无有效缓存时为空列表） */
    private List<PersonaFragment> cachedPersonaFragments() {
        if (cachedPersonaRecall == null || !cachedPersonaRecall.completed()
                || !personaConfig.equals(cachedPersonaRecall.config())) {
            return List.of();
        }
        return cachedPersonaRecall.fragments();
    }

    // ===== 检索模式 =====

    private void startRetrieval() {
        // 链上已完成首次历史检索（含「结果为空」）：历史不再规划，但人设目标独立判断
        if (cachedRecall != null && cachedRecall.completed()) {
            if (needsPersonaRecall()) {
                startPlanning(false, null);
            } else {
                dispatchAssembled(flattenAlive(cachedRecall.blocks()), cachedPersonaFragments(), true);
            }
            return;
        }
        boolean personaNeeded = needsPersonaRecall();
        // 同步性判定：indexFor 的回调可能在缓存命中/构建失败时内联执行（此时透传同步派发结果），
        // 也可能在索引重建后异步执行（此时按「已受理」收束）。收束规则：一旦发出规划请求，
        // 本次触发即异步受理；未进入规划请求的路径全部同步透传
        HistoryRetrievalCache.indexFor(maid, fullHistory, overheard.archivedLibrary(), result -> {
            // 索引可能是在文本执行器线程算完再回主线程的；本回调统一在主线程执行
            if (!allowed()) {
                // 无许可（含总开关已关闭）：经取消收束并摘除准备登记，迟到的索引结果不再导出发言
                syncOutcome = StartOutcome.FAILED;
                cancel();
                return;
            }
            boolean historyNeeded = result.hasSearchableHistory();
            if (!historyNeeded && !personaNeeded) {
                // 没有可检索历史、也不需要人设召回：直接正式生成，不发送规划请求。
                // 回报「已完成、结果为空」——同链后续轮次不再重复尝试。
                // 「不需要人设召回」有两种来源：链上没有有效缓存，或是缓存仍然有效（此前某轮
                // 已完成该配置的召回）——后者必须把缓存片段带上，换成空列表等于把已命中的
                // 背景／爱好资料从本轮请求里抹掉
                reportHistoryRecall(List.of());
                dispatchAssembled(List.of(), cachedPersonaFragments(), false);
                return;
            }
            startPlanning(historyNeeded, result);
        });
    }

    /**
     * 发出共用的一次关键词规划（历史与人设两组关键词由同一次请求返回）。
     *
     * @param historyEnabled 历史目标是否启用（不需要时提示词要求返回空数组，消费端同样强制忽略）
     * @param result         历史检索索引结果；历史目标未启用时为 null
     */
    private void startPlanning(boolean historyEnabled, HistoryRetrievalCache.Result result) {
        boolean personaEnabled = needsPersonaRecall();
        if (!historyEnabled && !personaEnabled) {
            dispatchAssembled(List.of(), cachedPersonaFragments(), false);
            return;
        }
        planningHistoryEnabled = historyEnabled;
        planningPersonaEnabled = personaEnabled;
        // 静默取消的出口接回本会话：状态表的超时／保存人设／状态移除取消的是规划本身，
        // 没有这条回边，外层不再收到任何取消——互聊请求身份、活动链与配对锁都留在原地没人收尾
        planning = new KeywordPlanningSession(maid, chatManager, site, historyEnabled, personaEnabled,
                this::planningMessages, this::allowed,
                plan -> onPlanned(plan, historyEnabled, personaEnabled, result), this::cancel, false);
        planning.start();
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
                SelfTalkPrompts.buildKeywordPlanInput(language, systemPrefix, planningRecentDialogue(),
                        latestSelfTalkMessage(), windowMessages, environment, triggerText,
                        planningPersonaEnabled ? personaConfig.draft() : null,
                        planningHistoryEnabled, planningPersonaEnabled, overheardPlanningMaterial(),
                        previousOutput, errorText)));
        return messages;
    }

    /**
     * 规划结果处理：两组关键词分别进入各自的索引，随后按原历史模式组装。
     * <p>
     * 规划会话已在终局时按目标开关过滤过关键词（未启用的目标恒为空），此处再按实际启用的目标
     * 分别检索，不会因为模型多给了关键词就发起不该有的检索。
     */
    private void onPlanned(KeywordPlanningSession.Plan plan, boolean historyEnabled,
                           boolean personaEnabled, HistoryRetrievalCache.Result result) {
        planning = null;
        if (!allowed()) {
            // 规划迟到且已无许可（插话中断、请求作废、总开关关闭）：整体收束并摘除准备登记
            cancel();
            return;
        }
        List<LLMMessage> recalledBlocks = List.of();
        boolean historyRecalled = false;
        if (historyEnabled && result != null) {
            Recalled recall = retrieve(plan.historyKeywords(), result);
            recalledBlocks = SelfTalkHistoryAssembler.flattenBlocks(recall.historyBlocks());
            historyRecalled = !recalledBlocks.isEmpty();
            overheard.recallArchived(recall.overheardGroups());
            reportHistoryRecall(recall.historyBlocks());
        } else if (mode == HistoryContextMode.RETRIEVAL) {
            if (cachedRecall != null && cachedRecall.completed()) {
                // 本轮只为另一个目标重新规划（人设变更等），历史召回链上已有成品：
                // 把它原样带回来。清空等于用一次单目标规划抹掉本轮已有的历史上下文
                recalledBlocks = flattenAlive(cachedRecall.blocks());
                historyRecalled = !recalledBlocks.isEmpty();
            } else {
                // 检索模式下历史目标本轮未发起检索且链上无结果：回报「已完成、结果为空」。
                // 链上已有结果时该回报本就是空操作（同一女仆同一链只生效一次）
                reportHistoryRecall(List.of());
            }
        }
        List<PersonaFragment> fragments = List.of();
        if (personaEnabled) {
            fragments = MaidPersonaRuntime.recall(maid, personaConfig, plan.personaKeywords());
            reportPersonaRecall(personaConfig, fragments);
        } else {
            // 反向情形：本轮只为历史目标规划，人设缓存若仍有效则沿用，不传空把另一目标清掉
            fragments = cachedPersonaFragments();
        }
        dispatchAssembled(recalledBlocks, fragments, historyRecalled);
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

    /** 把召回结果按块回报给链（同一女仆同一链只生效一次；空结果同样回报） */
    private void reportHistoryRecall(List<List<LLMMessage>> blocks) {
        if (recallReporter == null || historyReported) {
            return;
        }
        historyReported = true;
        recallReporter.reportHistory(blocks);
    }

    /** 把人设召回结果回报给链（配置快照一并带上；空结果同样算已完成） */
    private void reportPersonaRecall(MaidPersonaConfig config, List<PersonaFragment> fragments) {
        if (recallReporter == null) {
            return;
        }
        recallReporter.reportPersona(config, fragments);
    }

    /** 检索结果：正常聊天块（回原历史区消息）与命中偷听档案分组（按编号） */
    private record Recalled(List<List<LLMMessage>> historyBlocks, List<List<Long>> overheardGroups) {
    }

    /**
     * 按关键词跑 BM25，并把命中块映射回各自来源：正常块回原始历史区消息，偷听块回档案分组编号。
     * <p>
     * 合并池最多 3 个完整块，两类共用这一个上限；偷听块不经正常历史列表，作为独立背景资料组装。
     */
    private Recalled retrieve(List<String> keywords, HistoryRetrievalCache.Result result) {
        List<DialogueBlock> blocks = result.index().search(keywords);
        if (blocks.isEmpty()) {
            return new Recalled(List.of(), List.of());
        }
        List<List<LLMMessage>> recalled = new ArrayList<>(blocks.size());
        List<List<Long>> overheardGroups = new ArrayList<>();
        for (DialogueBlock block : blocks) {
            HistoryRetrievalCache.OverheardCandidate candidate = result.overheardAt(block.startIndex());
            if (candidate != null) {
                // 偷听块：回到 store 校验该女仆档案绑定，再由背景块组装，不塞进正常历史列表
                overheardGroups.add(candidate.ids());
                continue;
            }
            List<LLMMessage> messages = SelfTalkHistoryAssembler.selectRecalledMessages(
                    fullHistory, result.searchable(), List.of(block));
            if (!messages.isEmpty()) {
                recalled.add(messages);
            }
        }
        return new Recalled(List.copyOf(recalled), List.copyOf(overheardGroups));
    }

    // ===== 组装与派发 =====

    /**
     * 按<b>原历史模式</b>组装并派发。
     * <p>
     * 人设召回块只进入本轮最后一条 user 消息的动态资料区（与当前情境相邻），
     * 不进入历史区、不进检索库、也不伪装成过去的 assistant 发言。
     *
     * @param recalledBlocks   检索模式召回的历史块（已按历史顺序展平）；其他模式为空
     * @param personaFragments 本轮的人设召回片段（可能为空：未命中即整段省略）
     * @param recallNote       是否追加「召回片段是历史资料」的说明（仅历史召回需要）
     */
    private StartOutcome dispatchAssembled(List<LLMMessage> recalledBlocks,
                                           List<PersonaFragment> personaFragments, boolean recallNote) {
        String personaBlock = MaidPersonaRuntime.buildRecalledBlock(personaFragments, language);
        StartOutcome outcome = switch (mode) {
            case FULL -> dispatchFull(personaBlock);
            case COMPACT -> dispatchReduced(null, personaBlock, false);
            case RETRIEVAL -> dispatchReduced(recalledBlocks, personaBlock, recallNote);
        };
        syncOutcome = outcome;
        return outcome;
    }
    /** 链上缓存的历史块需先按当前历史区复核有效性，再按历史顺序展平（与首次检索的成品同构） */
    private List<LLMMessage> flattenAlive(List<List<LLMMessage>> blocks) {
        return SelfTalkHistoryAssembler.flattenBlocks(
                SelfTalkHistoryAssembler.retainAliveBlocks(blocks, fullHistory));
    }

    /**
     * 全量：保留现有历史组装行为，不加新的历史说明段。
     * <p>
     * 全量模式不观测历史召回，链进入交付等待（不是「等不到」）——否则同链下一轮会在
     * 前一跳尚未交付时就派发，破坏串行语义。
     */
    private StartOutcome dispatchFull(String personaBlock) {
        String overheardBlock = overheard.block();
        List<LLMMessage> history = rebuildHistoryWithSelfTalk();
        List<LLMMessage> messages = new ArrayList<>(systemPrefix);
        messages.addAll(history);
        messages.addAll(windowMessages);
        String prompt = basePrompt + SelfTalkContexts.latestSelfTalkNote(language, !selfTalkMessages.isEmpty())
                + SelfTalkContexts.recalledHistoryNote(language, false);
        messages.add(LLMMessage.userChat(maid, renderFinalUserMessage(prompt, personaBlock, overheardBlock)));
        int historyCount = systemPrefix.size() + history.size();
        SelfTalkContexts.wrapSegments(maid, messages, historyCount, windowMessages.size(), sourceIndex);
        return deliver(messages);
    }

    /**
     * 精简：不注入玩家聊天原文，只保留已有摘要（在 systemPrefix 内）、
     * 最近一条可识别自话与当前互聊窗口。
     *
     * @param recalledBlocks 检索模式的召回块；精简模式传 null
     * @param personaBlock   人设召回块（可能为空串）
     * @param recallNote     是否追加「召回片段是历史资料」的说明（仅检索模式）
     */
    private StartOutcome dispatchReduced(List<LLMMessage> recalledBlocks, String personaBlock,
                                         boolean recallNote) {
        String overheardBlock = overheard.block();
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
        messages.add(LLMMessage.userChat(maid, renderFinalUserMessage(prompt, personaBlock, overheardBlock)));
        SelfTalkContexts.wrapSegments(maid, messages, historyCount, windowMessages.size(), sourceIndex);
        return deliver(messages);
    }

    /** 渲染本轮最终 user message：默认按环境快照渲染；注入了自定义渲染器（如欢迎语）时走调用方口径 */
    private String renderFinalUserMessage(String prompt, String personaBlock, String overheardBlock) {
        if (finalMessageRenderer != null) {
            return finalMessageRenderer.render(prompt, personaBlock, overheardBlock);
        }
        return SelfTalkContexts.renderConfiguredUserMessage(prompt, language, environment, personaBlock,
                overheardBlock);
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

    /**
     * 规划资料里的偷听背景：只给本次实际可用的近期片段与该女仆可检索档案的摘要文本，
     * 不把整份档案或全服其他消息送给模型。没有内容时返回空串（整段省略）。
     */
    private String overheardPlanningMaterial() {
        if (overheard == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String block = overheard.block();
        if (!block.isBlank()) {
            sb.append(block);
        }
        for (OverheardChatStore.Group group : overheard.archivedLibrary()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(group.text());
        }
        return sb.toString();
    }

    private boolean allowed() {
        return !dispatched && !cancelled && maid.isAlive()
                && Config.ENABLED.get()
                && (stillAllowed == null || stillAllowed.get());
    }

    /** 正式派发（服务端主线程）：派发前再复核一次许可，超时或插话中断的迟到结果不会继续发请求 */
    private StartOutcome deliver(List<LLMMessage> messages) {
        if (!allowed()) {
            // 无许可出口同样收束：作废会话并摘除准备登记，不留待生成登记
            settle(false);
            cancel();
            return StartOutcome.FAILED;
        }
        dispatched = true;
        // 进入正式派发即不再是「待生成准备」：此后关闭总开关不能经本会话取消当前正式回答
        releasePreparation();
        boolean overheardEvent = overheard.hasEvent();
        if (dispatcher.dispatch(new PreparedRequest(List.copyOf(messages), mode, overheardEvent))) {
            // 只有同步派发成功才提交实际带入的编号；ACCEPTED_ASYNC 不是提交条件
            overheard.commit();
            reportOverheardRound(overheard.renderedRecentIds());
            settle(true);
            overheard.close();
            return StartOutcome.DISPATCHED;
        }
        MaidSelfTalkMod.LOGGER.warn("Failed to dispatch history-mode request for maid {}", maid.getId());
        settle(false);
        overheard.close();
        return StartOutcome.FAILED;
    }

    /** 首次正式派发时回报本轮实际注入的偷听编号（同链后续轮次复用同一轮背景，不重复追加有效历史） */
    private void reportOverheardRound(List<Long> ids) {
        if (recallReporter == null || overheardReported) {
            return;
        }
        overheardReported = true;
        recallReporter.reportOverheardRound(ids);
    }

    /** 派发失败（含被中断／请求作废）时回报空召回：失败同样算「首次检索完成」，链不得继续等待 */
    private void settle(boolean delivered) {
        if (!delivered) {
            reportHistoryRecall(List.of());
            // 首次派发失败也要回报偷听本轮资料（空表），链不得无限等待首次轮次
            reportOverheardRound(List.of());
        }
    }
}
