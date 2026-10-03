package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.maidmod.selftalk.history.KeywordPlanParser;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * 静默关键词规划回调（历史上下文检索模式专用）。
 * <p>
 * 与正式回复的结构差异，逐条对应计划要求：
 * <ul>
 *   <li>{@code needAddTools = false}：模型看不到任何工具定义，天然不会调用工具；</li>
 *   <li>{@code shouldCacheTokenUsage() = false}：规划 token 不覆盖 {@code lastChatTokenUsage}，
 *       否则会把玩家上下文压缩的判断依据改成规划的用量；</li>
 *   <li>{@code onSuccess} <b>不调</b>父类：不写历史、不发气泡、不发事件、不播报，
 *       自己解析 {@link ResponseChat#getChatText()} 后把关键词交给上层；</li>
 *   <li>{@code onFailure} <b>不调</b>父类：内部规划失败不向玩家抛技术错误，
 *       也不触发会误改玩家聊天计数的路径（{@code LLMCallbackMixin} 已把本类排除在计数之外）；</li>
 *   <li>{@code onFunctionCall} <b>不调</b>父类：绝不执行任何游戏工具，
 *       直接按「不符合规划输出格式」处理，进入同一纠正预算。</li>
 * </ul>
 * 不修改全局 Tool 注册表，不拦截或重写供应商 HTTP 请求，也不依赖供应商支持 JSON Schema 模式。
 * <p>
 * token 费用与玩家配额仍由上游 LLM 客户端照常计入（记账发生在客户端响应层，与回调类型无关），
 * 静默回调不绕过配额。
 */
public class KeywordPlanCallback extends LLMCallback {

    /** 规划结果消费者：交由上层决定是纠正还是继续（本类不自行重试） */
    private final Consumer<Outcome> onOutcome;

    /**
     * 规划结果。
     *
     * @param ok        是否解析成功（空数组算成功）
     * @param keywords  解析出的关键词（成功时非 null，可能为空）
     * @param errorText 供纠正提示使用的简短具体错误说明（格式错误时非 null；
     *                  传输类失败为 {@code null}，上层据此区分「不消耗纠正预算」的出口）
     * @param rawOutput 模型本次的原始输出（供纠正请求回显；工具调用或传输失败时为简短说明）
     */
    public record Outcome(boolean ok, List<String> keywords, String errorText, String rawOutput) {

        static Outcome success(List<String> keywords, String rawOutput) {
            return new Outcome(true, keywords, null, rawOutput);
        }

        static Outcome failure(String errorText, String rawOutput) {
            return new Outcome(false, List.of(), errorText, rawOutput);
        }
    }

    public KeywordPlanCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                               Consumer<Outcome> onOutcome) {
        super(chatManager, messages);
        this.onOutcome = onOutcome;
        // 规划请求绝不带工具：既避免误执行游戏工具，也让输出形状收敛到纯 JSON
        this.needAddTools = false;
    }

    /** 规划 token 不计入上下文压缩依据 */
    @Override
    public boolean shouldCacheTokenUsage() {
        return false;
    }

    /**
     * 模型改去调工具（不符合规划输出格式）：不执行任何工具，直接判为格式错误，
     * 与 JSON 解析失败走同一条纠正预算。
     */
    @Override
    public void onFunctionCall(Message choice, LLMClient client) {
        deliver(Outcome.failure("返回了工具调用，而不是 JSON 对象", "[tool_calls]"));
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // 不调父类：规划结果不写历史、不上屏、不触发任何玩家可见行为。
        // 用 getChatText() 解析而不是 toString()——后者会追加 TTS 分隔部分（"chat---tts"）。
        String raw = responseChat.getChatText();
        KeywordPlanParser.Result result = KeywordPlanParser.parse(raw);
        deliver(result.ok() ? Outcome.success(result.keywords(), raw)
                : Outcome.failure(result.error(), raw));
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        // 不调父类：避免向玩家显示内部规划错误，也避免走上游的聊天计数路径。
        // 传输/配额/认证类失败由上层按「非格式错误」处理（不消耗纠正预算）：
        // errorText 传 null 是这两类出口的区分标志。
        deliver(Outcome.failure(null, null));
    }

    /** 结果只投递一次；重复投递（上游偶发的多路径回调）由本标志挡掉 */
    private boolean delivered;

    private synchronized void deliver(Outcome outcome) {
        if (delivered) {
            return;
        }
        delivered = true;
        if (onOutcome != null) {
            onOutcome.accept(outcome);
        }
    }
}
