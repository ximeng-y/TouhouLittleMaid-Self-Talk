package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.HistoryMessagesCheck;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.UserPromptContexts;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 女仆自话服务：新公开入口（本 mod 定义，供状态机调用）。
 * <p>
 * 消息流与玩家 chat 完全同构，保证 LLM 提供商上下文前缀缓存一致：
 * <ol>
 *   <li>@Invoker 调 {@code MaidAIChatManager.getMessages} 拿到 [system 设定, 摘要, ...历史] 前缀；</li>
 *   <li>拼入提示词：自话走 {@link SelfTalkContexts#buildConfiguredUserMessage}（按玩家三态偏好选择环境信息，
 *       含固定上下文前缀与感知段）；欢迎语保留旧环境口径（分类级随机 + TLM 固定上下文，不读偏好、不消费事件），
 *       历史组装与自话一样按玩家历史上下文模式；</li>
 *   <li>user 消息<b>不写入</b> TLM 历史（系统内部消息，不出现在聊天记录 UI 中）；
 *       assistant 回复由 {@link SelfTalkCallback} 的父类逻辑写入历史，自动纳入原生聊天记录界面；</li>
 *   <li>发送 {@link SelfTalkCallback}，回复返回后执行遗忘检查。</li>
 * </ol>
 * 无人设（customSetting 为空且无模型设定文件）的女仆直接跳过，绝不自动生成人设。
 */
public final class MaidSelfTalkService {

    /** "主人在身边"判定半径（格） */
    private static final double OWNER_NEARBY_RANGE = 16.0;

    private MaidSelfTalkService() {
    }

    /**
     * 触发一次自话/欢迎。
     *
     * @param maid          女仆
     * @param welcome       是否为欢迎语（欢迎语视为一次自话，同样受保留条数控制）
     * @param keep          当前态的自言自语保留上下文条数
     * @param broadcastRange 聊天框广播半径（格）
     * @return 是否实际发起（前置检查未通过时为 false）
     */
    public static boolean triggerSelfTalk(EntityMaid maid, boolean welcome, int keep, double broadcastRange) {
        return triggerSelfTalk(maid, welcome, keep, broadcastRange, null);
    }

    /**
     * 触发一次自话/欢迎。
     *
     * @param maid           女仆
     * @param welcome        是否为欢迎语（欢迎语视为一次自话，同样受保留条数控制）
     * @param keep           当前态的自言自语保留上下文条数
     * @param broadcastRange 聊天框广播半径（格）
     * @param cachedRecall   互聊链上已完成的首次召回结果；非 null 时检索模式直接复用、不再规划
     *                       （自话与欢迎语不走互聊链，传 null）
     * @return 是否实际发起（前置检查未通过时为 false）
     */
    public static boolean triggerSelfTalk(EntityMaid maid, boolean welcome, int keep, double broadcastRange,
                                          HistoryContextSession.CachedRecall cachedRecall) {
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null) {
            return false;
        }
        if (!AIConfig.LLM_ENABLED.get()) {
            return false;
        }
        LLMSite site = chatManager.getLLMSite();
        if (site == null || !site.enabled()) {
            return false;
        }
        // 无人设（无自定义设定且无模型默认设定）→ 跳过，不自动生成人设
        if (chatManager.customSetting.isBlank() && chatManager.getSetting().isEmpty()) {
            return false;
        }

        // 自话语言：优先女仆已记录的聊天语言（玩家 chat 过则为客户端语言，保证上下文前缀缓存一致），
        // 否则用配置默认（TLM 官方模型设定多为英文，配置默认 zh_cn 保证中文输出）
        String selfTalkLanguage = SelfTalkContexts.sanitizeLanguage(StringUtils.isBlank(chatManager.chatLanguage)
                ? Config.SELF_TALK_LANGUAGE.get() : chatManager.chatLanguage);

        // 组装与玩家 chat 同构的消息前缀（语言影响设定占位符的替换），并做 tool 消息清洗
        List<LLMMessage> messages = SelfTalkContexts.fetchCleanedMessages(chatManager, selfTalkLanguage, "self-talk");
        if (messages == null) {
            return false;
        }
        int historyCount = messages.size();

        // 互聊窗口手动拼接：让自话能读到最近保留的互聊上下文，但不进 TLM 历史
        SelfTalkState.State chatState = SelfTalkState.get(maid.getId());
        List<LLMMessage> interWindow = new ArrayList<>(chatState.windowInterChatMsgs.size());
        for (SelfTalkState.InterChatWindowEntry entry : chatState.windowInterChatMsgs) {
            interWindow.add(entry.message());
        }
        for (LLMMessage wm : interWindow) {
            messages.add(wm);
        }
        try {
            HistoryMessagesCheck.checkMessages(messages);
        } catch (Throwable t) {
            MaidSelfTalkMod.LOGGER.warn("HistoryMessagesCheck after inter window failed, self-talk skipped", t);
            return false;
        }

        boolean ownerNearby = isOwnerNearby(maid);
        String prompt = welcome ? SelfTalkPrompts.WELCOME
                : (ownerNearby ? SelfTalkPrompts.SELF_TALK_OWNER_NEARBY : SelfTalkPrompts.SELF_TALK);
        // 拼装顺序：硬编码提示词 + 语言指令 + Tool 策略段(仅 Tool 开启时) + 自定义 Prompt(贴在指令主体后)。
        // 自定义段不得进入 SelfTalkPrompts 常量、也不得出现在格式引导(第 5 条)之前——见 SelfTalkPrompts 类注释红线。
        prompt = prompt + SelfTalkContexts.languageInstruction(selfTalkLanguage)
                + SelfTalkContexts.toolPolicyBlock(maid, selfTalkLanguage, false)
                + SelfTalkContexts.customPromptBlock(maid, selfTalkLanguage);

        // 欢迎语与自话共用同一套历史上下文模式组装（全量/精简/检索对欢迎语同样生效）；
        // 环境采集保留欢迎语原有口径：分类级随机 + TLM 固定上下文，不读三态偏好、不消费感知事件——
        // 通过自定义最终消息渲染器实现，会话的环境快照传 null（检索规划输入对此 null 安全）
        HistoryContextMode mode = PlayerSettingsStore.getHistoryContextModeForMaid(
                maid.level().getServer(), maid);
        SelfTalkContexts.EnvironmentSnapshot environment = welcome ? null
                : SelfTalkContexts.collectEnvironment(maid, selfTalkLanguage);
        SelfTalkHistoryAssembler.HistoryLayout layout =
                SelfTalkHistoryAssembler.split(messages, historyCount, interWindow.size());
        boolean toolEnabled = PlayerSettingsStore.isToolCallEnabledForMaid(maid.level().getServer(), maid);
        // 令牌在会话开始这一刻捕获：检索模式的规划可能耗时很久，若等到正式派发时才取，
        // 清空恰好发生在规划期间就会被漏掉（迟到结果会照常写档案）
        SelfTalkRequestToken token = SelfTalkRequestToken.capture(maid.getId());
        HistoryContextSession session = new HistoryContextSession(maid, chatManager, site, selfTalkLanguage,
                prompt, mode, environment, layout.systemPrefix(), layout.history(), layout.window(),
                cachedRecall, token::isValid,
                prepared -> dispatchSelfTalk(maid, chatManager, site, prepared, welcome, keep, broadcastRange,
                        toolEnabled, token),
                null,
                welcome ? p -> UserPromptContexts.addContext(maid, p + SelfTalkContexts.buildRandomContext(maid))
                        : null);
        session.start();
        // 全量/精简模式同步完成；检索模式可能仍在规划，此时返回 true（本次触发已受理）
        return true;
    }

    /**
     * 派发自话请求（消息列表已按模式组装完毕）。
     * <p>
     * Tool 判定在派发时取（dispatcher 顺延队列是延迟派发的，入队时不判定）。
     */
    private static boolean dispatchSelfTalk(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                            List<LLMMessage> messages, boolean welcome, int keep,
                                            double broadcastRange, boolean toolEnabled, SelfTalkRequestToken token) {
        // 标记进行中（防重入）
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.selfTalkPending = true;
        state.selfTalkPendingSinceTick = maid.level().getServer().getTickCount();

        LLMClient client = site.client();
        try {
            SelfTalkCallback callback = new SelfTalkCallback(chatManager, messages, welcome, keep,
                    broadcastRange, toolEnabled, token);
            state.currentSelfTalkCallback = callback;
            client.chat(callback);
        } catch (Throwable t) {
            // client.chat 同步阶段可能抛异常（如 site.url 非法导致 URI.create 失败、header 构造异常）：
            // 清掉进行中标记避免该女仆自话永久卡死，绝不向上抛（调用方可能处于实体 tick 路径）
            state.selfTalkPending = false;
            state.selfTalkPendingSinceTick = -1;
            state.currentSelfTalkCallback = null;
            MaidSelfTalkMod.LOGGER.warn("Failed to dispatch self-talk request for maid {}", maid.getId(), t);
            return false;
        }
        return true;
    }

    /**
     * 自话/欢迎回复返回后（服务端主线程）执行：把本次回复并入自话计数窗口并做遗忘检查。
     * <p>
     * 遗忘规则：当前自话窗口（从玩家上一次正常 chat 起）内保留条数触碰上限时，
     * <b>只从有效自话上下文移除</b>窗口内除本次外的全部记录，仅保留本次——
     * 展示档案完全不受影响（旧记录仍可在聊天记录界面里查看）。
     * 玩家发起 chat 时窗口重置（旧自话记录"赦免"，继续留在有效上下文里，计数重新开始）。
     *
     * @param record 本次回复新建的独立记录；为空（无可写档案／已被清空作废）时只复位 pending
     */
    public static void onSelfTalkFinished(EntityMaid maid, SelfTalkCallback callback, AutonomousChatRecord record) {
        SelfTalkState.State state = SelfTalkState.peek(maid.getId());
        if (state != null && state.currentSelfTalkCallback == callback) {
            state.selfTalkPending = false;
            state.selfTalkPendingSinceTick = -1;
            state.currentSelfTalkCallback = null;
        }
        // 自话回复已进独立档案：原始 TLM 玩家历史没变，但“最近一条自话”变了——只做失效标记，
        // 下次检索时来源序列不变即复用，不白白重新分词（见 HistoryRetrievalCache）。
        // 注意：独立记录不进检索库，检索库仍只含玩家对话
        HistoryRetrievalCache.invalidate(maid);
        if (record == null) {
            return;
        }
        // 计数窗口记录的是本次已在有效上下文里的那条记录（按身份，便于精确移除）
        state = SelfTalkState.get(maid.getId());
        state.windowSelfTalkMsgs.add(record);

        int keep = callback.getKeepSelfTalkCount();
        if (state.windowSelfTalkMsgs.size() >= keep && state.windowSelfTalkMsgs.size() > 1) {
            // 从有效自话上下文移除窗口内除本次外的全部记录（仅保留本次）；展示档案不动
            List<AutonomousChatRecord> toRemove = new ArrayList<>(
                    state.windowSelfTalkMsgs.subList(0, state.windowSelfTalkMsgs.size() - 1));
            AutonomousChatHistory archive = AutonomousChatHistoryHost.of(maid);
            if (archive != null) {
                archive.removeFromValidContext(toRemove);
            }
            state.windowSelfTalkMsgs.removeAll(toRemove);
        }
    }

    /**
     * 玩家发起 chat（请求已真实派发）：清空自话/互聊计数窗口（打断连续，计数重新开始），
     * 并重新计时自话与互聊冷却。
     * <p>
     * 内容保留：自话记录已写进独立档案（自动遗忘只影响有效上下文，展示档案不动），
     * 互聊记录已在 normalChat HEAD 注入本次请求的上下文
     * （见 {@link com.maidmod.selftalk.mixin.MaidAIChatManagerMixin}），
     * 这里清空的只是运行时计数窗口，不丢已注入内容、也不动已建立的有效自话。
     */
    public static void onPlayerChatStart(EntityMaid maid) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.playerChatCount++;
        state.playerChatSinceTick = maid.level().getServer().getTickCount();
        state.windowSelfTalkMsgs.clear();
        state.windowInterChatMsgs.clear();
        // 玩家 chat 会写入新的玩家对话：检索索引可能变化，只做失效标记，下次检索时再比对来源序列
        HistoryRetrievalCache.invalidate(maid);
        resetSelfTalkCooldown(maid, state);
        resetInterChatCooldown(maid, state);
    }

    /**
     * 自话冷却重新计时（区间随机，与正常触发后一致）。
     * 玩家对女仆发起 chat 后调用，避免 chat 结束后立刻触发自话。
     * 区间按当前态取：主人在线用态 1，否则用态 2。
     */
    private static void resetSelfTalkCooldown(EntityMaid maid, SelfTalkState.State state) {
        int minSeconds;
        int maxSeconds;
        if (maid.getOwner() != null) {
            minSeconds = Config.STATE1_MIN_INTERVAL.get();
            maxSeconds = Config.STATE1_MAX_INTERVAL.get();
        } else {
            minSeconds = Config.STATE2_MIN_INTERVAL.get();
            maxSeconds = Config.STATE2_MAX_INTERVAL.get();
        }
        long serverTick = maid.level().getServer().getTickCount();
        state.nextTriggerTick = serverTick + Config.randomIntervalTicks(minSeconds, maxSeconds);
    }

    /** 互聊冷却重新计时（与自话冷却独立，唯一打断来源同为玩家主动 chat） */
    private static void resetInterChatCooldown(EntityMaid maid, SelfTalkState.State state) {
        long serverTick = maid.level().getServer().getTickCount();
        state.nextInterChatTriggerTick = serverTick + Config.randomIntervalTicks(
                Config.INTER_CHAT_MIN_INTERVAL.get(), Config.INTER_CHAT_MAX_INTERVAL.get());
    }

    /** 玩家 chat 结束（成功或失败）：解除一条在途计数 */
    public static void onPlayerChatEnd(EntityMaid maid) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        if (state.playerChatCount > 0) {
            state.playerChatCount--;
        }
        // 仅当全部在途 chat 结束时清零计时，连发场景下保留最后一条的置位时刻供超时兜底
        if (state.playerChatCount == 0) {
            state.playerChatSinceTick = -1;
        }
    }

    /** 主人是否在身边（在线且在判定半径内） */
    private static boolean isOwnerNearby(EntityMaid maid) {
        var owner = maid.getOwner();
        return owner != null && maid.distanceToSqr(owner) <= OWNER_NEARBY_RANGE * OWNER_NEARBY_RANGE;
    }

    /**
     * 手动清空记忆（{@code clearAllChatMemory}）后的服务端收尾：作废本 mod 在途请求
     * （服务端主线程调用）。
     * <p>
     * 做法是推进清空世代（见 {@link SelfTalkRequestToken}），令清空前捕获的一切令牌失效：
     * <ul>
     *   <li>本女仆在途的自话／欢迎语：迟到结果不写档案、不进有效上下文、不发事件、不广播，
     *       pending 复位也按回调归属校验，不会清掉清空之后新请求的状态；</li>
     *   <li>本女仆在途的互聊请求：作废会话与已派发的正式回调，并作废本女仆所在链——
     *       与主人插话同语义（目标禁言 + 链终止），确保另一侧的旧链不能重新向被清空女仆投递消息。</li>
     * </ul>
     * 只处理本 mod 的请求，不扩展为全面重写 TLM 普通玩家请求的取消机制。
     */
    public static void invalidateRequestsOnClear(EntityMaid maid) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.clearGeneration++;
        SelfTalkRequestToken.advanceClearedUpTo(maid.getId(), state.clearGeneration);
        // 清空后旧窗口/计数一律不保留：它们引用的是已被隐藏的旧记录
        state.windowSelfTalkMsgs.clear();
        state.windowInterChatMsgs.clear();
        state.currentSelfTalkCallback = null;
        // 互聊：走主人插话同一套收尾（禁言 + 中断链 + 丢弃顺延），另一侧不会再把消息投递进来
        InterChatChain chain = InterChatChain.activeFor(maid);
        if (chain != null) {
            long chainId = chain.id();
            chain.interruptByOwnerChat(maid);
            SelfTalkDispatcher.dropQueuedForChain(maid, chainId);
            InterChatRequest request = chain.requestOf(maid);
            if (request != null) {
                request.cancel();
            }
            InterChatChain.release(chain);
        } else {
            InterChatRequest leftover = state.currentInterChatRequest;
            if (leftover != null) {
                SelfTalkDispatcher.dropQueuedForChain(maid, leftover.chainId());
                leftover.cancel();
            }
        }
        // 牌面（pending）随作废的请求一并复位：作废回调不会再走正常终态回来清它
        state.selfTalkPending = false;
        state.selfTalkPendingSinceTick = -1;
        state.interChatPending = false;
        state.interChatPendingSinceTick = -1;
    }
}
