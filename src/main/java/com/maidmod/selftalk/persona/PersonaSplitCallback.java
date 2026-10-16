package com.maidmod.selftalk.persona;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.maidmod.selftalk.AgentTweaksLifecycleBridge;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.network.MaidPersonaSplitResponsePayload;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * 静默「AI 拆分人设」回调。
 * <p>
 * 与 {@code KeywordPlanCallback} 同构的静默构造方式：
 * <ul>
 *   <li>{@code needAddTools = false}：模型看不到任何工具定义；</li>
 *   <li>{@code shouldCacheTokenUsage() = false}：拆分 token 不覆盖 {@code lastChatTokenUsage}；</li>
 *   <li>{@code onSuccess} <b>不调</b>父类：不写历史、不发气泡、不发事件、不播报，
 *       只读取 {@link ResponseChat#getChatText()}（由 onTextCall mixin 保留的
 *       未按 {@code ---} 分割的原文）交给解析器；</li>
 *   <li>{@code onFailure} <b>不调</b>父类：不向玩家抛技术错误（私信由服务统一发送，
 *       只说「整理失败」，不泄露异常或密钥）；</li>
 *   <li>{@code onFunctionCall} <b>不调</b>父类：绝不执行任何游戏工具，按格式失败收敛，
 *       并先调用生命周期 {@code complete} 释放上游记账。</li>
 * </ul>
 * 结果只投递一次；重复投递（上游偶发的多路径回调）由本标志挡掉。
 * 投递可能在响应线程发生，回主线程的调度由 {@link PersonaSplitService} 负责。
 */
public class PersonaSplitCallback extends LLMCallback {

    /** 拆分结果消费者：交由服务层统一收尾（本类不自行重试） */
    private final Consumer<Outcome> onOutcome;

    /**
     * 拆分结果。
     *
     * @param ok         是否拆分成功
     * @param draft      拆分出的四栏草稿（成功时非 null；失败时为空占位）
     * @param resultCode 固定结果码（见 {@link MaidPersonaSplitResponsePayload} 常量）
     */
    public record Outcome(boolean ok, MaidPersonaDraft draft, String resultCode) {

        static Outcome success(MaidPersonaDraft draft) {
            return new Outcome(true, draft, MaidPersonaSplitResponsePayload.RESULT_OK);
        }

        static Outcome failure(String resultCode) {
            return new Outcome(false, MaidPersonaDraft.EMPTY, resultCode);
        }
    }

    public PersonaSplitCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                                Consumer<Outcome> onOutcome) {
        // subagents=true 只利用父类的气泡开关：拆分请求从源头不创建等待气泡
        super(chatManager, messages, true);
        this.onOutcome = onOutcome;
        // 拆分请求绝不带工具：避免误执行游戏工具，也让输出形状收敛到纯 JSON
        this.needAddTools = false;
    }

    /** 拆分 token 不计入上下文压缩依据 */
    @Override
    public boolean shouldCacheTokenUsage() {
        return false;
    }

    /**
     * 模型改去调工具（不符合拆分输出格式）：不执行任何工具，直接判为格式错误。
     */
    @Override
    public void onFunctionCall(Message choice, LLMClient client) {
        // 工具调用不被视为可交付的拆分结果：先通知上游「本次请求已走完终态」，再投递格式失败
        AgentTweaksLifecycleBridge.complete(this);
        deliver(Outcome.failure(MaidPersonaSplitResponsePayload.RESULT_FORMAT));
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // 不调父类：拆分结果不写历史、不上屏、不触发任何玩家可见行为。
        // 解析的是 LLMOpenAIClientMixin 保留的完整原文——单参 ResponseChat 构造器
        // 会按 --- 切分并删除分隔符，切分结果无法还原，toString() 同样不能。
        String raw = responseChat.getChatText();
        PersonaSplitParser.Result result = PersonaSplitParser.parse(raw);
        deliver(result.ok() ? Outcome.success(result.draft())
                : Outcome.failure(MaidPersonaSplitResponsePayload.RESULT_FORMAT));
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        // 不调父类：传输/配额/认证类失败统一按 network 结果码收敛，细节不外泄
        deliver(Outcome.failure(MaidPersonaSplitResponsePayload.RESULT_NETWORK));
    }

    /**
     * 静默收敛本拆分回调（服务层调用）：不再导出结果、不再触发上层任何动作。
     * <p>
     * 用于超时清扫、玩家登出与服务器停止时结束在途请求——上游可能仍在飞行，
     * 但迟到结果已无意义，必须被挡在这里。
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
