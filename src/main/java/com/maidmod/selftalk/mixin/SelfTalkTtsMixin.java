package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.SelfTalkRequestToken;
import com.maidmod.selftalk.SelfTalkTtsGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 TLM 的远程 TTS 回调补上自话／欢迎语的清空令牌。
 * <p>
 * 父类 {@code LLMCallback.onSuccess} 只<b>发起</b> TTS：它新建一个裸 {@code TTSCallback} 并启动
 * 一次语音合成的网络往返，真正向主人交付（语音封包、聊天文本、失败红字）发生在那之后。
 * 「回复已返回并开始合成 → 玩家清空记忆 → 合成结果到达」这条时序下，裸回调不知道本请求已被作废，
 * 会把清空前的内容照常播报与上屏。本 mixin 在回调<b>构造时</b>取走发起线程上登记的令牌
 * （{@code SelfTalkCallback.onSuccess} 在调用父类、发起 TTS 的同步区间内建立该登记，
 * 见 {@link SelfTalkTtsGate}），并在两个<b>主线程交付任务</b>（{@code onSuccess}／{@code onFailure}
 * 各自 {@code submit} 到主线程的那个 Runnable）发送任何内容之前判定。
 * <p>
 * 判定必须落在主线程交付任务里而不是响应线程的 {@code onSuccess}/{@code onFailure} 入口：
 * 后者只是<b>发起</b>投递，真正发包／上屏／红字在随后的主线程任务中执行，若只在响应线程判一次，
 * 「判定通过 → 玩家清空 → 主线程任务才执行」仍会把旧内容送到主人眼前。放到交付任务开头判定，
 * 与实际发送在同一主线程任务内原子完成。构造时清空必然还没发生，只有交付的当下才可能已经作废。
 * 作废时只清本条自己的等待气泡（转投主线程执行），不产生语音、聊天文本或错误红字。
 * <p>
 * 令牌为 null（普通玩家聊天、互聊等未登记路径）时两个注入点都不介入，
 * 交付逻辑与未安装本 mod 时完全一致。
 */
@Mixin(TTSCallback.class)
public abstract class SelfTalkTtsMixin {

    /** 本条 TTS 所属请求的清空令牌；未登记路径为 null */
    @Unique
    private SelfTalkRequestToken maid_self_talk$token;

    /** 本条 TTS 自己的等待气泡 id（构造实参直接带进来，不再另找读口） */
    @Unique
    private long maid_self_talk$waitingChatBubbleId;

    /**
     * 构造时取走发起线程上的登记。
     * <p>
     * TLM 的 {@code tts} 在自己的调用栈内直接 {@code new TTSCallback(...)}，
     * 而该调用栈正处在自话／欢迎语建立的登记区间内，因此构造时刻登记必然已就位。
     */
    @Inject(method = "<init>", remap = false, at = @At("TAIL"))
    private void maid_self_talk$captureToken(EntityMaid maid, String chatText, long waitingChatBubbleId,
                                             CallbackInfo ci) {
        this.maid_self_talk$token = SelfTalkTtsGate.currentToken();
        this.maid_self_talk$waitingChatBubbleId = waitingChatBubbleId;
    }

    @Inject(method = "lambda$onSuccess$1", remap = false, at = @At("HEAD"), cancellable = true)
    private void maid_self_talk$guardTtsSuccessDelivery(CallbackInfo ci) {
        if (isInvalidated()) {
            // 清空作废：不发语音封包、不写旧聊天文本，只回收本条自己的等待气泡
            discardOwnWaitingBubble();
            ci.cancel();
        }
    }

    @Inject(method = "lambda$onFailure$0", remap = false, at = @At("HEAD"), cancellable = true)
    private void maid_self_talk$guardTtsFailureDelivery(CallbackInfo ci) {
        if (isInvalidated()) {
            // 清空作废：不显示 TTS 错误红字、不补写旧聊天文本，只回收本条自己的等待气泡
            discardOwnWaitingBubble();
            ci.cancel();
        }
    }

    @Unique
    private boolean isInvalidated() {
        SelfTalkRequestToken token = this.maid_self_talk$token;
        return token != null && !token.isValid();
    }

    @Unique
    private void discardOwnWaitingBubble() {
        SelfTalkTtsGate.discardWaitingBubble(((TTSCallback) (Object) this).getMaid(),
                this.maid_self_talk$waitingChatBubbleId);
    }
}
