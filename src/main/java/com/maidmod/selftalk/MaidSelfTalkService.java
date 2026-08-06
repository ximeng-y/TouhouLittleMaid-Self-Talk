package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.UserPromptContexts;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.mixin.MaidAIChatManagerAccessor;
import com.mojang.datafixers.util.Pair;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 女仆自话服务：新公开入口（本 mod 定义，供状态机调用）。
 * <p>
 * 消息流与玩家 chat 完全同构，保证 LLM 提供商上下文前缀缓存一致：
 * <ol>
 *   <li>@Invoker 调 {@code MaidAIChatManager.getMessages} 拿到 [system 设定, 摘要, ...历史] 前缀；</li>
 *   <li>随机纳入 1~3 类游戏情境信息（位置/附近实体/装备/效果/用户/状态/世界）拼入提示词，防同质化；</li>
 *   <li>提示词经 {@link UserPromptContexts#addContext} 注入游戏状态后作为 user 消息追加；</li>
 *   <li>user 消息<b>不写入</b> TLM 历史（系统内部消息，不出现在聊天记录 UI 中）；
 *       assistant 回复由 {@link SelfTalkCallback} 的父类逻辑写入历史，自动纳入原生聊天记录界面；</li>
 *   <li>发送 {@link SelfTalkCallback}，回复返回后执行遗忘检查。</li>
 * </ol>
 * 无人设（customSetting 为空且无模型设定文件）的女仆直接跳过，绝不自动生成人设。
 */
public final class MaidSelfTalkService {

    /** 可随机纳入的情境信息分类（TLM 内置 Context 分类 id） */
    private static final List<String> CONTEXT_CATEGORIES = List.of(
            "nearby_entities", "equipment", "position", "user", "effects", "status", "world");
    /** 女仆状态分类（含当前工作状态 work_task 等） */
    private static final String STATUS_CATEGORY = "status";
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
        String selfTalkLanguage = StringUtils.isBlank(chatManager.chatLanguage)
                ? Config.SELF_TALK_LANGUAGE.get() : chatManager.chatLanguage;

        // 组装与玩家 chat 同构的消息前缀（语言影响设定占位符的替换）
        List<LLMMessage> messages;
        try {
            messages = ((MaidAIChatManagerAccessor) (Object) chatManager)
                    .invokeGetMessages(chatManager, selfTalkLanguage);
        } catch (Throwable t) {
            // accessor 未注册或 TLM 版本不兼容时的兜底：放弃本次触发，绝不向上抛
            // （调用方可能处于实体 tick 路径，异常会导致女仆被崩溃恢复机制移除）
            MaidSelfTalkMod.LOGGER.error("Failed to invoke MaidAIChatManager.getMessages, self-talk skipped", t);
            return false;
        }
        if (messages.isEmpty()) {
            // 双保险：设定为空走 TLM 会自动生成人设，此处直接放弃本次触发
            return false;
        }

        // 随机纳入情境信息，让自话内容贴合当下、不同质化；
        // 主人在身边时强制纳入女仆状态（含当前工作状态），并使用对应的提示词
        boolean ownerNearby = isOwnerNearby(maid);
        String prompt = welcome ? Config.WELCOME_PROMPT.get()
                : (ownerNearby ? Config.SELF_TALK_PROMPT_OWNER_NEARBY.get() : Config.SELF_TALK_PROMPT.get());
        prompt = prompt + languageInstruction(selfTalkLanguage) + buildRandomContext(maid, ownerNearby);

        // 与玩家 chat 相同的 context 注入，保证消息结构与缓存前缀一致
        String message = UserPromptContexts.addContext(maid, prompt);
        messages.add(LLMMessage.userChat(maid, message));

        // 标记进行中（防重入）
        SelfTalkState.get(maid.getId()).selfTalkPending = true;

        LLMClient client = site.client();
        // 埋点日志：定位"聊天框消息重复"问题（一次触发应当只有一次 chat 请求）
        MaidSelfTalkMod.LOGGER.info("SELF-TALK trigger: maid={} welcome={} pending={}",
                maid.getId(), welcome, SelfTalkState.get(maid.getId()).selfTalkPending);
        client.chat(new SelfTalkCallback(chatManager, messages, welcome, keep, broadcastRange));
        return true;
    }

    /**
     * 自话/欢迎回复返回后（服务端主线程）执行：记录本次回复并做遗忘检查。
     * <p>
     * 遗忘规则：当前自话窗口（从玩家上一次正常 chat 起）内保留条数触碰上限时，
     * 删除窗口内除本次外的全部自话记录，仅保留本次——防止自话记录无限撑大上下文。
     * 玩家发起 chat 时窗口重置（旧自话记录"赦免"保留在上下文中，计数重新开始）。
     */
    public static void onSelfTalkFinished(EntityMaid maid, SelfTalkCallback callback) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.selfTalkPending = false;

        // 本次回复的 assistant 消息：父类 onSuccess 已写入历史尾部
        Deque<LLMMessage> deque = callback.getChatManager().getHistory().getDeque();
        LLMMessage last = deque.peekLast();
        if (last == null) {
            return;
        }
        state.windowSelfTalkMsgs.add(last);

        int keep = callback.getKeepSelfTalkCount();
        if (state.windowSelfTalkMsgs.size() >= keep && state.windowSelfTalkMsgs.size() > 1) {
            // 删除窗口内除本次外的所有自话记录（仅保留本次）
            List<LLMMessage> toRemove = new ArrayList<>(
                    state.windowSelfTalkMsgs.subList(0, state.windowSelfTalkMsgs.size() - 1));
            deque.removeAll(toRemove);
            state.windowSelfTalkMsgs.removeAll(toRemove);
        }
    }

    /** 玩家发起 chat：窗口重置（计数重新开始，旧自话记录赦免保留在上下文中） */
    public static void onPlayerChatStart(EntityMaid maid) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        state.playerChatPending = true;
        state.windowSelfTalkMsgs.clear();
    }

    /** 玩家 chat 结束（成功或失败）：解除进行中标记 */
    public static void onPlayerChatEnd(EntityMaid maid) {
        SelfTalkState.get(maid.getId()).playerChatPending = false;
    }

    /**
     * 随机纳入 1~3 类游戏情境信息，拼为提示词尾段。
     * <p>
     * 主人在身边（{@code ownerNearby}）时，女仆状态分类（含当前工作状态）必定纳入，
     * 其余分类照常随机。
     */
    private static String buildRandomContext(EntityMaid maid, boolean ownerNearby) {
        List<String> pool = new ArrayList<>(CONTEXT_CATEGORIES);
        List<String> picked = new ArrayList<>();
        if (ownerNearby) {
            // 主人在身边：必须注入女仆状态（含当前工作状态）
            picked.add(STATUS_CATEGORY);
            pool.remove(STATUS_CATEGORY);
        }
        int count = 1 + maid.getRandom().nextInt(3);
        int remaining = Math.min(count - picked.size(), pool.size());
        for (int i = 0; i < remaining; i++) {
            // 从剩余分类中随机抽取一个（RandomSource 非 java.util.Random，手写抽取）
            picked.add(pool.remove(maid.getRandom().nextInt(pool.size())));
        }

        List<String> parts = new ArrayList<>();
        for (String category : picked) {
            List<String> values = GameContextRegister.getContext(category, maid);
            if (!values.isEmpty()) {
                parts.add(String.join("；", values));
            }
        }
        if (parts.isEmpty()) {
            return StringUtils.EMPTY;
        }
        return "\n\n当前情境：" + String.join("；", parts) + "。";
    }

    /** 主人是否在身边（在线且在判定半径内） */
    private static boolean isOwnerNearby(EntityMaid maid) {
        var owner = maid.getOwner();
        return owner != null && maid.distanceToSqr(owner) <= OWNER_NEARBY_RANGE * OWNER_NEARBY_RANGE;
    }

    /**
     * 按配置语言生成输出语言指令，追加到提示词中。
     * TLM 官方模型人设设定多为英文，若不显式声明语言，模型可能跟随英文设定输出英文。
     */
    private static String languageInstruction(String language) {
        return switch (language) {
            case "zh_cn", "zh" -> "\n\n请始终用简体中文说话。";
            case "en_us", "en" -> "\n\nPlease always speak in English.";
            default -> "\n\n请始终用%s说话。".formatted(language);
        };
    }
}
