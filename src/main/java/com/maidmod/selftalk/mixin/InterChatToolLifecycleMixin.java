package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.maidmod.selftalk.InterChatCallback;
import com.maidmod.selftalk.SelfTalkCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 关闭互聊请求作废后的<b>异步工具连环</b>；并切断本 mod 工具过程对 TLM 历史的写入。
 * <p>
 * 第一部分：TLM 的工具生命周期在 {@code onSingleCall}
 * （执行单个工具）→ {@code lambda$executeSingleToolCall$5}（结果合并）→
 * {@code lambda$onFunctionCall$2}（最终 whenComplete：把 sideCallbacks/nextCallback
 * 经 {@code client.chat} 派发下去）这三处异步入口上继续运行。
 * <p>
 * 作废后的互聊请求（{@link InterChatCallback#isCancelled()}）或已被手动清空作废的自话请求
 * （{@link SelfTalkCallback#isRequestInvalidated()}）即使主回调的
 * {@code isStillAllowed()} 已拦在主线程入口，这三处仍可能在响应线程继续：
 * <ul>
 *   <li>{@code onSingleCall}：会继续 {@code tool.onCallAsync} 执行工具、刷新等待气泡；</li>
 *   <li>{@code lambda$executeSingleToolCall$5}：会写入工具错误历史、构造子流程回调；</li>
 *   <li>{@code lambda$onFunctionCall$2}：会把下一轮回调派发出去（工具连环最关键的出口）。</li>
 * </ul>
 * 对已作废请求，这三处一律短路：不再执行任何工具、不再写历史、不再派发下一轮——
 * 迟到结果在源头被挡，玩家不可见，也不会触碰新请求的任何资源。
 * <p>
 * 第二部分（历史写入切断）：宿主是 {@link SelfTalkCallback} 或 {@link InterChatCallback} 时，
 * 工具过程<b>不写入 TLM 待压缩历史</b>——这是从源头隔离，不是「先写后按 role 扫全表删」：
 * <ul>
 *   <li>{@code onFunctionCall} 内的 {@code addAssistantHistory(String, List)}；</li>
 *   <li>{@code addToolResult} 内的 {@code addToolHistory(String, String)}；</li>
 *   <li>{@code lambda$executeSingleToolCall$5} 子 agent 分支直接写入的 {@code addToolHistory}。</li>
 * </ul>
 * 只跳过持久历史写入，保留请求内的 {@code messages.add(...)}、工具调用配对、结果合并
 * 及下一轮派发；工具过程既不进入 TLM 历史，也不进入独立展示档案。
 * <p>
 * 守卫只在「宿主对象是自话／互聊回调且该请求已失效」时生效：玩家 chat 与规划回调不受影响。
 * <p>
 * 四处注入都声明 {@code cancellable = true}：处理器对命中守卫的回调执行 {@code cancel()} 或
 * {@code setReturnValue(...)}，未声明可取消会导致 Mixin 生成的 CallbackInfo 不可取消，
 * 第一次命中就抛 {@code CancellationException} 而不是按预期短路。
 * <p>
 * Mixin 会校验 handler 的参数类型与顺序；可访问类型直接使用目标方法的实际类型，
 * 仅私有嵌套类型 {@code LLMCallback.ToolBatchResult} 使用 {@code @Coerce Object} 接收并原样返回。
 * <p>
 * 目标方法均为 {@code LLMCallback} 的实例方法（private lambda 亦同），handler 同为实例方法，
 * 与双线既有 mixin 约定一致。
 */
@Mixin(LLMCallback.class)
public abstract class InterChatToolLifecycleMixin {

    /**
     * 单个工具执行入口：请求作废后不再真正执行工具（也就不会刷新气泡、不会产生后续异步链）。
     * 直接返回「已完成的 nextCallback」——与 TLM 未执行工具的返回形状一致，
     * 合并阶段保留原 batchResult，批次中的后续工具仍由本入口逐个跳过。
     */
    @Inject(method = "onSingleCall", cancellable = true, at = @At("HEAD"))
    private void maid_self_talk$blockSingleToolCall(ToolCall toolCall, LLMCallback callback, LLMClient client,
                                                    CallbackInfoReturnable<CompletableFuture<LLMCallback>> cir) {
        if (maid_self_talk$isStopped()) {
            cir.setReturnValue(CompletableFuture.completedFuture(callback));
        }
    }

    /**
     * 单工具结果合并：作废后跳过错误历史写入与子流程回调构造，<b>返回本次捕获的原批次结果</b>
     * 而非 null——TLM 的 executeToolBatch 经 {@code thenCompose} 串联同一批工具，
     * 下一环一进入就读取 {@code batchResult.nextCallback()}，null 会让批次中途取消时
     * 下一个 thenCompose 直接 NPE。断链由 {@link #maid_self_talk$blockToolChainDispatch}
     * 在最终派发守卫完成，本处只保证「已作废批次的中间合并环不炸、不写历史」。
     */
    @Inject(method = "lambda$executeSingleToolCall$5", cancellable = true, at = @At("HEAD"))
    private void maid_self_talk$blockToolBatchMerge(ToolCall toolCall, LLMCallback nextCallback, @Coerce Object batchResult,
                                                    boolean isLastTool, LLMCallback callback, Throwable throwable,
                                                    CallbackInfoReturnable<Object> cir) {
        if (maid_self_talk$isStopped()) {
            cir.setReturnValue(batchResult);
        }
    }

    /**
     * 整批工具结束后的最终派发：作废后不再把 sideCallbacks/nextCallback 交给 {@code client.chat}——
     * 这是工具连环的最后一个出口，拦在这里即断链。
     */
    @Inject(method = "lambda$onFunctionCall$2", cancellable = true, at = @At("HEAD"))
    private void maid_self_talk$blockToolChainDispatch(CallbackInfo ci) {
        if (maid_self_talk$isStopped()) {
            ci.cancel();
        }
    }

    /**
     * 等待气泡刷新（两个重载最终都进入本方法）：作废请求的气泡由回调清理时按本轮 id 删除，
     * 此处不再刷新，避免对已作废回调的残留 id 产生任何更新。
     */
    @Inject(method = "refreshWaitingChatBubble", cancellable = true, at = @At("HEAD"))
    private void maid_self_talk$blockBubbleRefresh(CallbackInfo ci) {
        if (maid_self_talk$isStopped()) {
            ci.cancel();
        }
    }

    /**
     * 本回调是否已「停止」：异步工具链上的四处入口与气泡刷新据此短路。
     * <p>
     * 覆盖两类失效：
     * <ul>
     *   <li>互聊请求作废（{@link InterChatCallback#isCancelled()}：主人插话、手动清空、超时、链终止）；</li>
     *   <li>自话／欢迎语请求被手动清空作废（{@link SelfTalkCallback#isRequestInvalidated()}）——
     *       判定只查线程安全的世代墓碑，不在响应线程读 {@link com.maidmod.selftalk.SelfTalkState} 的普通表。</li>
     * </ul>
     * 普通玩家 chat 回调与静默规划回调不属于任一宿主，行为不变。Agent-Tweaks 的生命周期桥仍由既有请求
     * 生命周期负责，这里不重复通知 {@code cancel/complete}。
     */
    private boolean maid_self_talk$isStopped() {
        if ((Object) this instanceof InterChatCallback interChat) {
            return interChat.isCancelled();
        }
        if ((Object) this instanceof SelfTalkCallback selfTalk) {
            return selfTalk.isRequestInvalidated();
        }
        return false;
    }

    // ===== 工具过程的历史写入切断（源头隔离，不写 TLM 待压缩历史） =====

    /**
     * {@code onFunctionCall} 内写入的 {@code assistant(tool_calls)} 历史：本 mod 宿主跳过。
     * 请求内的 {@code messages.add(assistantChat(...))} 不受影响（在重定向的这一行之外），
     * 因此工具调用配对与后续轮次照常工作。
     */
    @Redirect(method = "onFunctionCall",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatManager;addAssistantHistory(Ljava/lang/String;Ljava/util/List;)V"))
    private void maid_self_talk$skipToolCallHistory(MaidAIChatManager chatManager, String message,
                                                     List<ToolCall> toolCalls) {
        if (maid_self_talk$isAutonomousHost()) {
            return;
        }
        chatManager.addAssistantHistory(message, toolCalls);
    }

    /**
     * {@code addToolResult} 内写入的 tool 结果历史：本 mod 宿主跳过。
     * 请求内的 {@code messages.add(toolChat(...))} 不受影响。
     */
    @Redirect(method = "addToolResult",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatManager;addToolHistory(Ljava/lang/String;Ljava/lang/String;)V"))
    private void maid_self_talk$skipToolResultHistory(MaidAIChatManager chatManager, String result, String toolId) {
        if (maid_self_talk$isAutonomousHost()) {
            return;
        }
        chatManager.addToolHistory(result, toolId);
    }

    /**
     * 子 agent 分支（{@code lambda$executeSingleToolCall$5}）直接写入的占位 tool 历史：本 mod 宿主跳过。
     * 该分支还负责把返回值换成新的 {@code ToolBatchResult}，因此只重定向写入行、
     * 不动方法本身的返回结构。
     */
    @Redirect(method = "lambda$executeSingleToolCall$5",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatManager;addToolHistory(Ljava/lang/String;Ljava/lang/String;)V"))
    private void maid_self_talk$skipSubAgentToolHistory(MaidAIChatManager chatManager, String result, String toolId) {
        if (maid_self_talk$isAutonomousHost()) {
            return;
        }
        chatManager.addToolHistory(result, toolId);
    }

    /**
     * 本回调是否属于「工具过程不进 TLM 历史」的宿主：自话／欢迎语与互聊回调。
     * <p>
     * 已被清空作废的自话请求同样覆盖——作废后仍在飞行的那一轮更不该留下任何 TLM 历史。
     * 普通玩家 chat 回调与静默规划回调不受影响（规划请求本身不带工具）。
     */
    private boolean maid_self_talk$isAutonomousHost() {
        return (Object) this instanceof InterChatCallback || (Object) this instanceof SelfTalkCallback;
    }
}
