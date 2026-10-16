package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.HistoryMessagesCheck;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.UserPromptContexts;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.mixin.MaidAIChatManagerAccessor;
import com.maidmod.selftalk.persona.PersonaFragment;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家聊天路径的按需人设召回会话。
 * <p>
 * 玩家聊天不容许「先发请求、召回结果事后补」——那会多出一条与玩家原话无关的回复。
 * 因此这条路径反过来做：<b>先推迟原生派发</b>，把静默规划跑完，再<b>恢复</b>原生方法，
 * 由原生代码完成唯一一次「原话入历史 + 建回调 + 发请求」。玩家看到的仍然是一次正常回复，
 * 只是晚了几百毫秒。
 * <p>
 * 与自动聊天（{@link HistoryContextSession}）的取舍差异，逐条对应计划要求：
 * <ul>
 *   <li><b>不写任何东西</b>：推迟期间不写玩家历史、不建 {@code LLMCallback}、
 *       不触发 TAIL 的正式聊天计数——这些全部留给恢复后的原生调用，各一次；</li>
 *   <li><b>不走历史召回</b>：玩家聊天的历史由 TLM 原生规则决定（全量注入），本会话只负责人设；</li>
 *   <li><b>不消费感知事件</b>：规划资料用的是原生 {@code UserPromptContexts} 的当前事实与玩家原话，
 *       不 drain 本 mod 留给自话／互聊的感知事件缓冲；</li>
 *   <li><b>不覆盖并发输入</b>：每次玩家输入各有自己的会话与结果，互不顶替。</li>
 * </ul>
 * 恢复靠 {@link MaidAIChatManagerAccessor#invokeNormalChat} 再调一次原生方法，入口钩子会随之重跑：
 * 主人插话与迁移是幂等的，历史合并与窗口注入<b>只在这一遍</b>执行（推迟那遍在注入之前就返回了），
 * 于是「只对正式消息列表执行一次」自然成立。
 * <p>
 * 原版模式或没有动态人设时根本不构造本会话，玩家聊天保持原有同步路径。
 * <p>
 * 线程约定：全部在服务端主线程执行（{@code normalChat} 由 {@code chat()} 在主线程调用，
 * 规划结果也由 {@link KeywordPlanningSession} 投递回主线程）。
 */
public final class PlayerPersonaChatSession {

    /**
     * 待恢复的玩家输入：女仆实体 ID → 该女仆名下尚未正式派发的会话。
     * <p>
     * 保留成列表而不是单个引用，是为了让同一位玩家连着发的几句话说各自算数——
     * 后一句不能把前一句顶掉。列表本身不参与派发顺序决策：每个会话在自己的规划结束后
     * 立刻恢复，谁先规划完谁先派发。
     */
    private static final Map<Integer, List<PlayerPersonaChatSession>> PENDING = new HashMap<>();

    /**
     * 仅在同步恢复调用期间有效的重入标识。
     * <p>
     * 恢复就是「再调一次原生 {@code normalChat}」，入口钩子会再跑一遍。没有这个标识，
     * 钩子会把刚恢复的这次调用又当成一次新输入去推迟，无限推迟下去。
     * 标识只在 {@link #performResume()} 里那一小段同步窗口内有效（恢复调用返回后立即清空），
     * 因此它只挡得住恢复自身，挡不住期间新到来的玩家输入。
     */
    private static PlayerPersonaChatSession resuming;

    private final EntityMaid maid;
    private final MaidAIChatManager chatManager;
    /** 玩家原话：入历史与最终请求都用它，不做任何改写 */
    private final String message;
    private final LLMClient chatClient;
    private final String language;
    /** 本次会话冻结的人设配置快照：保存新配置即重新准备，见 {@link #reprepareWithCurrentConfig()} */
    private MaidPersonaConfig personaConfig;
    /** 清空令牌：清空记忆后本次输入作废（恢复时核对，见 {@link #performResume()}） */
    private final SelfTalkRequestToken token;
    /** 原生消息副本：恢复时按原样交给原生方法（正式派发前可能按新配置整体重建） */
    private List<LLMMessage> messages;

    /** 在途静默规划（本会话只跑人设目标） */
    private KeywordPlanningSession planning;
    /** 本轮人设召回块（恢复时由 Redirect 插进玩家消息的动态上下文；空串表示不注入） */
    private String personaBlock = StringUtils.EMPTY;
    private boolean cancelled;
    /** 是否已恢复原生派发（终态标记，防重复恢复） */
    private boolean resumed;
    /**
     * 是否正在按新配置重建本次会话。
     * <p>
     * 重建时会静默结束旧规划，而规划会话的取消会回调本会话的 {@link #cancel()}；
     * 那个回调针对的是「旧快照已作废」，不是「本次输入作废」，必须在这里挡掉，
     * 否则保存一次人设就会把玩家刚说的话丢掉。
     */
    private boolean restarting;

    private PlayerPersonaChatSession(EntityMaid maid, MaidAIChatManager chatManager, String message,
                                     List<LLMMessage> messages, LLMClient chatClient, String language,
                                     MaidPersonaConfig personaConfig) {
        this.maid = maid;
        this.chatManager = chatManager;
        this.message = message;
        this.messages = messages;
        this.chatClient = chatClient;
        this.language = language;
        this.personaConfig = personaConfig;
        this.token = SelfTalkRequestToken.capture(maid.getId());
    }

    /**
     * 玩家聊天入口钩子的判定口：是否要推迟本次原生派发。
     * <p>
     * 返回 true 表示「已接管这次输入」，调用方必须取消本次 {@code normalChat} 执行；
     * 返回 false 表示按原有同步路径继续（原版模式、没有动态人设、以及恢复调用自身）。
     *
     * @param messages 原生已构建好的消息列表（本方法不改动它；恢复时原样交回原生方法）
     */
    public static boolean deferIfNeeded(EntityMaid maid, MaidAIChatManager chatManager, String message,
                                        List<LLMMessage> messages, LLMClient chatClient) {
        // 恢复调用自身：这已经是正式派发，不得再推迟
        if (resuming != null) {
            return false;
        }
        if (maid == null || chatManager == null || chatClient == null || !maid.isAlive()) {
            return false;
        }
        MaidPersonaConfig personaConfig = MaidPersonaRuntime.configOf(maid);
        if (!MaidPersonaRuntime.hasDynamicPersona(personaConfig)) {
            // 原版模式，或四栏模式但没有背景／爱好：没有人设可召回，不推迟
            return false;
        }
        if (chatManager.getLLMSite() == null) {
            return false;
        }
        String language = SelfTalkContexts.sanitizeLanguage(StringUtils.isBlank(chatManager.chatLanguage)
                ? Config.SELF_TALK_LANGUAGE.get() : chatManager.chatLanguage);
        PlayerPersonaChatSession session = new PlayerPersonaChatSession(maid, chatManager, message,
                new ArrayList<>(messages), chatClient, language, personaConfig);
        session.register();
        session.startPlanning();
        return true;
    }

    /**
     * 人设配置已变更（保存成功）后重新准备本会话。
     * <p>
     * 输入<b>不丢弃</b>：静默结束旧快照的规划，用新配置重跑一次规划再恢复原生派发。
     * 原生消息列表一并按新配置重建——四栏模式下它含「身份 → 性格」的核心正文，
     * 沿用旧的那份等于把旧人设发出去。
     */
    public static void onConfigChanged(EntityMaid maid) {
        List<PlayerPersonaChatSession> sessions = PENDING.get(maid.getId());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (PlayerPersonaChatSession session : List.copyOf(sessions)) {
            session.reprepareWithCurrentConfig();
        }
    }

    /**
     * 本轮人设召回块（供 {@code LLMMessage.userChat} 的 Redirect 读取）。
     * <p>
     * 只在同步恢复调用期间有值：不处于恢复窗口时返回空串，Redirect 照旧只包段标签。
     */
    public static String currentPersonaBlock() {
        PlayerPersonaChatSession session = resuming;
        return session == null ? StringUtils.EMPTY : session.personaBlock;
    }

    // ===== 规划 =====

    private void startPlanning() {
        planning = new KeywordPlanningSession(maid, chatManager, chatManager.getLLMSite(),
                // 玩家路径不做历史召回：历史注入规则由 TLM 原生决定，本会话只负责人设
                false, true, this::planningMessages, () -> !cancelled && !restarting && maid.isAlive(),
                plan -> onPlanned(plan.personaKeywords()), this::cancel, true);
        planning.start();
    }

    /**
     * 规划请求的消息列表：独立系统提示 + 一份「资料」用户消息。
     * <p>
     * 资料里只有原生 {@code UserPromptContexts} 的当前事实与玩家原话：这两样合起来就是
     * 「主人现在说了什么、在什么情况下说的」，足够判断该取哪些背景与爱好。
     * 环境快照传 null——本 mod 的感知事件缓冲留给自话／互聊，玩家输入不消费它。
     */
    private List<LLMMessage> planningMessages(String previousOutput, String errorText) {
        List<LLMMessage> messages = new ArrayList<>(2);
        messages.add(LLMMessage.systemChat(maid, SelfTalkPrompts.KEYWORD_PLAN_SYSTEM));
        messages.add(LLMMessage.userChat(maid, SelfTalkPrompts.buildKeywordPlanInput(language,
                systemPrefix(), List.of(), null, List.of(), null,
                UserPromptContexts.addContext(maid, message), personaConfig.draft(),
                false, true, previousOutput, errorText)));
        return messages;
    }

    /** 原生消息列表的前导 SYSTEM 段（四栏核心正文 + 可选摘要），规划资料里当「人设」用 */
    private List<LLMMessage> systemPrefix() {
        int end = 0;
        while (end < messages.size()
                && messages.get(end).role() == com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role.SYSTEM) {
            end++;
        }
        return List.copyOf(messages.subList(0, end));
    }

    private void onPlanned(List<String> personaKeywords) {
        planning = null;
        if (cancelled || resumed) {
            return;
        }
        List<PersonaFragment> fragments = MaidPersonaRuntime.recall(maid, personaConfig, personaKeywords);
        personaBlock = MaidPersonaRuntime.buildRecalledBlock(fragments, language);
        performResume();
    }

    // ===== 恢复原生派发 =====

    private void performResume() {
        if (cancelled || resumed) {
            return;
        }
        resumed = true;
        unregister();
        // 正式派发前再次核对：清空记忆（令牌失效）或配置又变了，本次输入不再按旧快照发出
        if (!maid.isAlive() || !token.isValid()) {
            return;
        }
        if (!personaConfig.equals(MaidPersonaRuntime.configOf(maid))) {
            return;
        }
        resuming = this;
        try {
            ((MaidAIChatManagerAccessor) (Object) chatManager).invokeNormalChat(message, messages, chatClient);
        } catch (Throwable t) {
            MaidSelfTalkMod.LOGGER.warn("Failed to resume player chat for maid {}", maid.getId(), t);
        } finally {
            // 重入标识只在这次同步调用期间有效
            resuming = null;
        }
    }

    /** 按当前配置重新准备：旧快照的规划作废，消息列表重建，随后重新规划或直接恢复派发 */
    private void reprepareWithCurrentConfig() {
        if (cancelled || resumed) {
            return;
        }
        restarting = true;
        try {
            if (planning != null) {
                KeywordPlanningSession current = planning;
                planning = null;
                // 先摘引用再取消：取消会回调本会话的收尾，不能让收尾把新规划一起带走
                current.cancel();
            }
            MaidPersonaConfig current = MaidPersonaRuntime.configOf(maid);
            personaConfig = current;
            if (!MaidPersonaRuntime.hasDynamicPersona(current)) {
                // 新配置没有动态人设：不注入任何召回块，直接按原生路径派发
                personaBlock = StringUtils.EMPTY;
                performResume();
                return;
            }
            if (!rebuildNativeMessages()) {
                return;
            }
            startPlanning();
        } finally {
            restarting = false;
        }
    }

    /**
     * 按当前配置重建原生消息列表（走 TLM 原生 {@code getMessages} 的同一入口，
     * 因此四栏模式的核心正文、摘要与历史读取顺序与一次性构建时完全一致），并重跑发送前清洗。
     *
     * @return 是否可继续（新配置下已无可用人设时返回 false，本次输入到此结束）
     */
    private boolean rebuildNativeMessages() {
        List<LLMMessage> rebuilt;
        try {
            rebuilt = ((MaidAIChatManagerAccessor) (Object) chatManager)
                    .invokeGetMessages(chatManager, chatManager.chatLanguage);
        } catch (Throwable t) {
            MaidSelfTalkMod.LOGGER.warn("Failed to rebuild player chat messages for maid {}",
                    maid.getId(), t);
            return false;
        }
        if (rebuilt == null || rebuilt.isEmpty()) {
            // 新配置下已无可用的固定正文（身份、性格都空）：与一次性构建时一样，
            // 显示原生「缺少人设」提示，不静默吞掉玩家这次输入
            maid.getChatBubbleManager()
                    .addTextChatBubble("ai.touhou_little_maid.chat.llm.role_no_setting");
            return false;
        }
        try {
            HistoryMessagesCheck.checkMessages(rebuilt);
        } catch (Throwable t) {
            // 清洗失败一律放弃本次输入，绝不把可能破坏请求的列表发出去
            MaidSelfTalkMod.LOGGER.warn("HistoryMessagesCheck failed on player chat re-prepare for maid {}",
                    maid.getId(), t);
            return false;
        }
        messages = rebuilt;
        return true;
    }

    // ===== 生命周期 =====

    /** 作废本会话：结束在途规划、从待恢复名单摘除，本次输入不再恢复派发 */
    private void cancel() {
        if (cancelled || resumed || restarting) {
            // restarting：这次取消来自「按新配置重建」而非「本次输入作废」，输入必须留下
            return;
        }
        cancelled = true;
        if (planning != null) {
            KeywordPlanningSession current = planning;
            planning = null;
            current.cancel();
        }
        unregister();
    }

    private void register() {
        PENDING.computeIfAbsent(maid.getId(), id -> new ArrayList<>()).add(this);
    }

    private void unregister() {
        List<PlayerPersonaChatSession> sessions = PENDING.get(maid.getId());
        if (sessions == null) {
            return;
        }
        sessions.remove(this);
        if (sessions.isEmpty()) {
            PENDING.remove(maid.getId());
        }
    }
}
