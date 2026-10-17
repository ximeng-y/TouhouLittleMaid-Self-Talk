package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.SelfTalkTtsTranslationCallback.Outcome;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * TTS 翻译在途会话：登记为玩家聊天无关的在途计划（{@code playerChat=false}），
 * 清空记忆、人设变更、女仆状态移除与超时清扫都会经统一取消口静默收束。
 * <p>
 * 请求只含专用 SYSTEM 翻译规则 + USER 待翻译文本：不带历史、人设、环境、召回与工具，
 * 不做纠正循环——一次失败即按翻译失败收敛，调用方自行决定纯文本投放。
 * 全程服务端主线程；响应线程结果经 {@link #onServerThread} 投回。
 */
public final class SelfTalkTtsTranslationSession {

    private final EntityMaid maid;
    private final MaidAIChatManager chatManager;
    private final LLMSite site;
    private final List<LLMMessage> messages;
    private final Supplier<Boolean> stillAllowed;
    private final Consumer<Outcome> consumer;
    private final Runnable onCancelled;

    private SelfTalkState.PendingPlan registration;
    private SelfTalkTtsTranslationCallback activeCallback;
    private boolean finished;
    private boolean cancelled;

    public SelfTalkTtsTranslationSession(EntityMaid maid, MaidAIChatManager chatManager, LLMSite site,
                                         String text, String sourceLanguage, String targetLanguage,
                                         Supplier<Boolean> stillAllowed, Consumer<Outcome> consumer,
                                         Runnable onCancelled) {
        this.maid = maid;
        this.chatManager = chatManager;
        this.site = site;
        this.messages = List.of(
                LLMMessage.systemChat(maid, SelfTalkPrompts.TTS_TRANSLATION_SYSTEM),
                LLMMessage.userChat(maid, SelfTalkPrompts.buildTtsTranslationInput(
                        text, sourceLanguage, targetLanguage)));
        this.stillAllowed = stillAllowed;
        this.consumer = consumer;
        this.onCancelled = onCancelled;
    }

    /**
     * 登记在途计划并发出翻译请求（服务端主线程）。
     * <p>
     * 请求未成功发出（同步异常）时按翻译失败收束，调用方走纯文本投放。
     */
    public void start() {
        if (!allowed()) {
            cancel();
            return;
        }
        long nowTick = maid.level().getServer() == null ? 0L : maid.level().getServer().getTickCount();
        // playerChat=false：翻译窗口内女仆按「有在途计划」处理，但不属于玩家聊天清除范围
        registration = SelfTalkState.get(maid.getId()).registerPlan(nowTick, this::cancel, false);
        send();
    }

    /**
     * 静默取消：结束在途翻译请求、摘除登记，不投递任何结果。
     * <p>
     * 清空记忆、人设变更、女仆状态移除与超时清扫统一走这里；幂等。
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

    private void send() {
        if (!allowed()) {
            finish(Outcome.failure());
            return;
        }
        try {
            LLMClient client = site.client();
            SelfTalkTtsTranslationCallback callback = new SelfTalkTtsTranslationCallback(
                    chatManager, messages, outcome -> onServerThread(() -> onOutcome(outcome)));
            activeCallback = callback;
            client.chat(callback);
        } catch (Throwable t) {
            activeCallback = null;
            MaidSelfTalkMod.LOGGER.warn("Failed to dispatch TTS translation for maid {}, deliver text only",
                    maid.getId(), t);
            finish(Outcome.failure());
        }
    }

    private void onOutcome(Outcome outcome) {
        activeCallback = null;
        if (finished || cancelled) {
            return;
        }
        if (!allowed()) {
            cancel();
            return;
        }
        finish(outcome);
    }

    private void finish(Outcome outcome) {
        if (finished || cancelled) {
            return;
        }
        finished = true;
        unregister();
        consumer.accept(outcome);
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
