package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.maidmod.selftalk.InterChatCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 关闭互聊请求作废后的<b>异步工具连环</b>：TLM 的工具生命周期在 {@code onSingleCall}
 * （执行单个工具）→ {@code lambda$executeSingleToolCall$5}（结果合并）→
 * {@code lambda$onFunctionCall$2}（最终 whenComplete：把 sideCallbacks/nextCallback
 * 经 {@code client.chat} 派发下去）这三处异步入口上继续运行。
 * <p>
 * 作废后的互聊请求（{@link InterChatCallback#isCancelled()}）即使主回调的
 * {@code isStillAllowed()} 已拦在主线程入口，这三处仍可能在响应线程继续：
 * <ul>
 *   <li>{@code onSingleCall}：会继续 {@code tool.onCallAsync} 执行工具、刷新等待气泡；</li>
 *   <li>{@code lambda$executeSingleToolCall$5}：会写入工具错误历史、构造子流程回调；</li>
 *   <li>{@code lambda$onFunctionCall$2}：会把下一轮回调派发出去（工具连环最关键的出口）。</li>
 * </ul>
 * 对已作废请求，这三处一律短路：不再执行任何工具、不再写历史、不再派发下一轮——
 * 迟到结果在源头被挡，玩家不可见，也不会触碰新请求的任何资源。
 * <p>
 * 守卫只在「宿主对象是互聊回调且已被作废」时生效：自话/欢迎语/玩家 chat/规划回调不受影响。
 * <p>
 * 目标方法均为 {@code LLMCallback} 的实例方法（private lambda 亦同），handler 同为实例方法，
 * 与双线既有 mixin 约定一致。
 */
@Mixin(LLMCallback.class)
public abstract class InterChatToolLifecycleMixin {

    /**
     * 单个工具执行入口：请求作废后不再真正执行工具（也就不会刷新气泡、不会产生后续异步链）。
     * 直接返回「已完成的 nextCallback」——与 TLM 未执行工具的返回形状一致，
     * {@code lambda$executeSingleToolCall$5} 的 {@code returned == nextCallback} 分支原样收束。
     */
    @Inject(method = "onSingleCall", remap = false, at = @At("HEAD"))
    private void maid_self_talk$blockSingleToolCall(CallbackInfoReturnable<CompletableFuture> cir,
                                                    LLMCallback callback) {
        if ((Object) this instanceof InterChatCallback ic && ic.isCancelled()) {
            cir.setReturnValue(CompletableFuture.completedFuture(callback));
        }
    }

    /**
     * 单工具结果合并：作废后跳过错误历史写入与子流程回调构造，直接返回 null 短路。
     * 返回值只会被 {@code lambda$onFunctionCall$2} 消费，而后者同样被本 mixin 短路，
     * 因此 null 绝不会被解引用；同批其它工具也不会再被执行。
     */
    @Inject(method = "lambda$executeSingleToolCall$5", remap = false, at = @At("HEAD"))
    private void maid_self_talk$blockToolBatchMerge(CallbackInfoReturnable<Object> cir) {
        if ((Object) this instanceof InterChatCallback ic && ic.isCancelled()) {
            cir.setReturnValue(null);
        }
    }

    /**
     * 整批工具结束后的最终派发：作废后不再把 sideCallbacks/nextCallback 交给 {@code client.chat}——
     * 这是工具连环的最后一个出口，拦在这里即断链。
     */
    @Inject(method = "lambda$onFunctionCall$2", remap = false, at = @At("HEAD"))
    private void maid_self_talk$blockToolChainDispatch(CallbackInfo ci) {
        if ((Object) this instanceof InterChatCallback ic && ic.isCancelled()) {
            ci.cancel();
        }
    }

    /**
     * 等待气泡刷新（两个重载最终都进入本方法）：作废请求的气泡由回调清理时按本轮 id 删除，
     * 此处不再刷新，避免对已作废回调的残留 id 产生任何更新。
     */
    @Inject(method = "refreshWaitingChatBubble", remap = false, at = @At("HEAD"))
    private void maid_self_talk$blockBubbleRefresh(CallbackInfo ci) {
        if ((Object) this instanceof InterChatCallback ic && ic.isCancelled()) {
            ci.cancel();
        }
    }
}
