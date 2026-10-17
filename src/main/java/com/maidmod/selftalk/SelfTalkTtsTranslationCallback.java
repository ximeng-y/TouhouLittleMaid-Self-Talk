package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.maidmod.selftalk.AgentTweaksLifecycleBridge;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * 静默 TTS 翻译回调（预制复用命中后目标语言不符时的一次性翻译请求）。
 * <p>
 * 与 {@code PersonaSplitCallback} 同构的静默构造方式：
 * <ul>
 *   <li>{@code needAddTools = false}：翻译请求不带任何工具；</li>
 *   <li>{@code shouldCacheTokenUsage() = false}：翻译 token 不覆盖 {@code lastChatTokenUsage}；</li>
 *   <li>{@code onSuccess} <b>不调</b>父类：不写历史、不上屏、不触发任何玩家可见行为，
 *       译文交还消费方；</li>
 *   <li>{@code onFailure} / {@code onFunctionCall} <b>不调</b>父类：一律判为翻译失败，
 *       没有纠正循环、没有全量生成兜底。</li>
 * </ul>
 * 结果只投递一次；消费方在主线程上收尾（投递与覆写）。
 */
public class SelfTalkTtsTranslationCallback extends LLMCallback {

    /** 翻译结果：成功带译文；失败时 translated 为 null */
    public record Outcome(boolean ok, String translated) {

        static Outcome success(String translated) {
            return new Outcome(true, translated);
        }

        static Outcome failure() {
            return new Outcome(false, null);
        }
    }

    private final Consumer<Outcome> onOutcome;

    public SelfTalkTtsTranslationCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                                          Consumer<Outcome> onOutcome) {
        // subagents=true 只利用父类的气泡开关：翻译请求从源头不创建等待气泡
        super(chatManager, messages, true);
        this.onOutcome = onOutcome;
        // 翻译请求绝不带工具：工具调用即翻译失败，也不得执行任何游戏工具
        this.needAddTools = false;
    }

    /** 翻译 token 不计入上下文压缩依据 */
    @Override
    public boolean shouldCacheTokenUsage() {
        return false;
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // 不调父类：译文不写历史、不上屏、不触发 TTS。
        // LLMOpenAIClientMixin 对本回调保留完整原文（单参构造器会按 --- 切分，
        // 译文里的分隔线会被误删），blank 即空回复，同样按失败收敛
        String translated = responseChat.getChatText();
        if (translated == null || translated.isBlank()) {
            deliver(Outcome.failure());
            return;
        }
        deliver(Outcome.success(translated));
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        // 不调父类：传输/配额/认证类失败按翻译失败收敛，不向玩家抛技术错误
        AgentTweaksLifecycleBridge.complete(this);
        deliver(Outcome.failure());
    }

    @Override
    public void onFunctionCall(Message choice, LLMClient client) {
        // 模型改去调工具（不符合翻译输出约定）：不执行任何工具，直接判为翻译失败
        AgentTweaksLifecycleBridge.complete(this);
        deliver(Outcome.failure());
    }

    /**
     * 静默收敛本翻译回调（会话取消时调用）：不再导出结果、不再触发上层任何动作。
     * 上游可能仍在飞行，迟到结果由 cancelled 标记挡在这里。
     */
    public void completeSilently() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        AgentTweaksLifecycleBridge.cancel(this);
    }

    /** 结果只投递一次；重复投递（上游偶发的多路径回调）由本标志挡掉 */
    private boolean delivered;
    /** 是否已被主动作废：作废后的迟到结果不导出任何动作（沉默收尾） */
    private volatile boolean cancelled;

    private synchronized void deliver(Outcome outcome) {
        if (delivered || cancelled) {
            return;
        }
        delivered = true;
        if (onOutcome != null) {
            onOutcome.accept(outcome);
        }
    }
}
