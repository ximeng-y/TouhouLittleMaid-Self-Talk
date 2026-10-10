package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSystemServices;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.SelfTalkTtsGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 TLM 的<b>系统 TTS</b>（{@code TTSSystemServices} 分支）补上自话／欢迎语的清空令牌判定。
 * <p>
 * 系统 TTS 不新建 {@code TTSCallback}（那条由 {@link SelfTalkTtsMixin} 守卫），而是在
 * {@code MaidAIChatManager.onPlaySoundLocal} 内直接 {@code server.submit} 一个主线程任务：
 * 该任务发送 {@code TTSSystemAudioToClientPackage} 并随后 {@code addLLMChatText} 上屏旧聊天文本。
 * 「回复已返回并发起系统 TTS → 玩家清空 → 主线程发声任务才执行」这条时序下，裸任务会把清空前的
 * 内容照常播报与上屏，违背清空作废语义（见 {@link com.maidmod.selftalk.SelfTalkRequestToken}）。
 * <p>
 * {@code onPlaySoundLocal} 在自话／欢迎语发起 TTS 的同步区间内被调用（仍在 {@code SelfTalkTtsGate.runWith}
 * 登记中），此处 HEAD 按本请求的等待气泡 id 把令牌寄存；随后投递的主线程发声任务在其 HEAD 取走并
 * 判失效——判定与实际发包／上屏在同一主线程任务内原子完成。作废时只回收本条自己的等待气泡，
 * 不发声、不上屏。令牌未寄存（普通玩家聊天等未登记路径）时两个注入点都不介入，行为与未装本 mod 时一致。
 */
@Mixin(MaidAIChatManager.class)
public abstract class SelfTalkSystemTtsMixin {

    @Inject(method = "onPlaySoundLocal", remap = false, at = @At("HEAD"))
    private void maid_self_talk$armSystemTts(String ttsId, String chatText, String ttsText, TTSConfig config,
                                             TTSSystemServices services, long waitingChatBubbleId, CallbackInfo ci) {
        SelfTalkTtsGate.armSystemTts(((MaidAIChatManager) (Object) this).getMaid(), waitingChatBubbleId);
    }

    @Inject(method = "lambda$onPlaySoundLocal$5", remap = false, at = @At("HEAD"), cancellable = true)
    private void maid_self_talk$guardSystemTtsDelivery(String chatText, String ttsText, TTSConfig config,
                                                       TTSSystemServices services, String ttsId,
                                                       long waitingChatBubbleId, CallbackInfo ci) {
        EntityMaid maid = ((MaidAIChatManager) (Object) this).getMaid();
        if (SelfTalkTtsGate.consumeSystemTtsInvalidated(maid, waitingChatBubbleId)) {
            SelfTalkTtsGate.discardWaitingBubble(maid, waitingChatBubbleId);
            ci.cancel();
        }
    }
}
