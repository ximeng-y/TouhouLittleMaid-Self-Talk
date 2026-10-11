package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.maidmod.selftalk.SelfTalkCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给自话／欢迎语<b>未开启 TTS 时</b>的聊天文本上屏补上清空令牌判定。
 * <p>
 * 关闭 TTS（或 TTS 站点不可用）时，{@code LLMCallback.onSuccess} 不发起语音合成，而是
 * {@code server.submit} 一个主线程任务，由本 mixin 注入的 {@code lambda$onSuccess$1} 把旧聊天文本
 * 写进气泡与主人聊天栏。这条投递与远程／系统 TTS 是同一类延迟交付，只是没有任何 TTS 回调或
 * 系统 TTS 注册可挂令牌，{@link com.maidmod.selftalk.SelfTalkTtsGate} 的两个中转口都覆盖不到它。
 * <p>
 * 外层 {@code SelfTalkCallback} 已在同一主线程任务内判过一次许可，但那次判定与这个延迟任务之间
 * 仍可能插进一次清空（「回复已返回 → 玩家清空 → 上屏任务才被调度」），裸任务会把清空前的文本
 * 照常送到主人眼前。因此在任务<b>执行当下</b>再判一次：作废则只回收本条自己的等待气泡，不上屏。
 * <p>
 * 只对 {@link SelfTalkCallback} 生效：TLM 普通玩家聊天（裸 {@code LLMCallback}）与
 * {@code InterChatCallback}（不调父类 {@code onSuccess}，已在自己的延迟任务内判定）都不受影响，
 * 行为与未安装本 mod 时一致。
 */
@Mixin(LLMCallback.class)
public abstract class SelfTalkLateDisplayMixin {

    @Inject(method = "lambda$onSuccess$1", remap = false, at = @At("HEAD"), cancellable = true)
    private void maid_self_talk$guardLateDisplay(String chatText, CallbackInfo ci) {
        if ((Object) this instanceof SelfTalkCallback selfTalk && !selfTalk.allowLateDisplay()) {
            ci.cancel();
        }
    }
}
