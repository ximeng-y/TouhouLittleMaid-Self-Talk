package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.PrebuiltAudioContext;
import com.maidmod.selftalk.PrebuiltAudioStore;
import com.maidmod.selftalk.SelfTalkTtsGate;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 预制压缩语音缓存的 TTS 入口分流（{@code MaidAIChatManager.tts} 头部）。
 * <p>
 * 该入口只在自话／欢迎语的登记区间内被本 mod 相关请求走到（普通玩家聊天不登记音频上下文），
 * 按 {@link SelfTalkTtsGate#currentAudio()} 取到的上下文分两路：
 * <ul>
 *   <li><b>缓存命中</b>（携带命中字节）：取消本次供应商调用，直接构造原生 {@code TTSCallback}
 *       播放缓存音频——构造仍发生在当前登记区间内，清空令牌与音频上下文被照常捕获，
 *       随后的主线程交付任务延续既有守卫；命中播放不重复入库、不触发任何网络往返；</li>
 *   <li><b>捕获</b>：冻结本次交付身份——声音键按<b>实际发起合成</b>的站点与音色计算，
 *       语言与文本取当次调用实参——然后照常走原方法发起供应商合成；
 *       合成字节由 {@code SelfTalkTtsMixin} 在 TTS 回调入口补上，最终按条目身份结算入库。</li>
 * </ul>
 * 供应商被排除（系统 TTS／Player2）时只跳过身份冻结，不改变原有调用：
 * 系统 TTS 分支本就不构造 {@code TTSCallback}，音频不会入库。
 * <p>
 * 兼容边界：{@code tts(TTSSite, String, String, long)} 对应 TLM {@code 1.5.3-forge+mc1.20.1}
 * （Modrinth）的字节码，升级或更换 TLM 构建须同步复核本类 method 绑定。
 */
@Mixin(MaidAIChatManager.class)
public abstract class SelfTalkPrebuiltTtsMixin {

    @Inject(method = "tts", remap = false, at = @At("HEAD"), cancellable = true)
    private void maid_self_talk$prebuiltAudioTts(TTSSite site, String chatText, String ttsText,
                                                 long waitingChatBubbleId, CallbackInfo ci) {
        PrebuiltAudioContext audio = SelfTalkTtsGate.currentAudio();
        if (audio == null) {
            // 未登记路径（普通玩家聊天等）或本次请求不参与缓存：完全原样
            return;
        }
        EntityMaid maid = ((MaidAIChatManager) (Object) this).getMaid();
        if (maid == null) {
            return;
        }
        if (audio.hasHitBytes()) {
            // 缓存命中：取消供应商调用，改播缓存音频（字节已在上下文里，无网络往返）。
            // 原生回调在登记区间内构造：其 init 捕获令牌与上下文，交付任务延续清空守卫
            ci.cancel();
            new TTSCallback(maid, chatText, waitingChatBubbleId).onSuccess(audio.hitBytes());
            return;
        }
        // 捕获路径：冻结交付身份，随后照常发起供应商合成；供应商被排除时不冻结——
        // 系统 TTS 无音频回调、Player2 音频不回传服务端，都不参与缓存
        if (!PrebuiltAudioStore.isExcludedProvider(site)
                && maid.level() instanceof ServerLevel serverLevel) {
            audio.noteDeliveryIdentity(serverLevel.getServer(), site,
                    ((MaidAIChatManager) (Object) this).getTTSModel(),
                    ((MaidAIChatManager) (Object) this).getTTSLanguage(), ttsText);
        }
    }
}
