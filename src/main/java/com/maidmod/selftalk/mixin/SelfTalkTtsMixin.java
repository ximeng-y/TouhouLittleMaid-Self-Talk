package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.PrebuiltAudioContext;
import com.maidmod.selftalk.SelfTalkRequestToken;
import com.maidmod.selftalk.SelfTalkTtsGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 TLM 的远程 TTS 回调补上自话／欢迎语的清空令牌与预制语音缓存捕获。
 * <p>
 * 父类 {@code LLMCallback.onSuccess} 只<b>发起</b> TTS：它新建一个裸 {@code TTSCallback} 并启动
 * 一次语音合成的网络往返，真正向主人交付（语音封包、聊天文本、失败红字）发生在那之后。
 * 「回复已返回并开始合成 → 玩家清空记忆 → 合成结果到达」这条时序下，裸回调不知道本请求已被作废，
 * 会把清空前的内容照常播报与上屏。本 mixin 在回调<b>构造时</b>取走发起线程上登记的令牌与音频上下文
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
 * 预制语音缓存（世界开关开启时）：合成成功入口 {@code onSuccess(byte[])} 的 HEAD 先把原始字节
 * 暂存进本请求的音频上下文（响应线程安全，内部有锁），再投递主线程结算任务——条目身份、
 * 交付身份与字节三者齐备且令牌有效时登记待保存；格式不符或已终态则幂等放弃。
 * 交付任务头部再补一次幂等结算与 TTS 现势重判：带音频上下文的请求若 TTS 已被关闭，
 * 不再发送音频包，只把聊天文本补上屏（等待气泡转正式气泡）——缓存入库照常完成。
 * 缓存命中播放的上下文（携带命中字节）不参与捕获与结算。
 * <p>
 * 令牌为 null（普通玩家聊天等未登记路径）时全部注入点都不介入，
 * 交付逻辑与未安装本 mod 时完全一致。
 * <p>
 * 兼容边界：{@code lambda$onSuccess$1}／{@code lambda$onFailure$0} 对应 TLM
 * {@code 1.5.3-forge+mc1.20.1}（Modrinth）的字节码。lambda 序号由编译器按宿主类内出现顺序生成，
 * 升级或更换 TLM 构建可能漂移，须同步复核本类 method 绑定
 * （{@code com.maidmod.selftalk.TlmMixinTargetTest} 已在 CI 校验）；交付时判定的语义不因此调整注入点。
 */
@Mixin(TTSCallback.class)
public abstract class SelfTalkTtsMixin {

    /** 本条 TTS 所属请求的清空令牌；未登记路径为 null */
    @Unique
    private SelfTalkRequestToken maid_self_talk$token;

    /** 本条 TTS 所属请求的预制音频上下文；未登记或本次请求不参与缓存时为 null */
    @Unique
    private PrebuiltAudioContext maid_self_talk$audio;

    /** 本条 TTS 的上屏聊天文本（构造实参直接带进来；TTS 现势关闭时仅文本上屏要用） */
    @Unique
    private String maid_self_talk$chatText;

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
        this.maid_self_talk$audio = SelfTalkTtsGate.currentAudio();
        this.maid_self_talk$chatText = chatText;
        this.maid_self_talk$waitingChatBubbleId = waitingChatBubbleId;
    }

    /**
     * 合成成功入口（响应线程）：把原始字节暂存进音频上下文，并投递主线程结算任务。
     * 命中播放的上下文（携带缓存字节）不参与捕获——它自己的播放路径不产生新音频。
     */
    @Inject(method = "onSuccess", remap = false, at = @At("HEAD"))
    private void maid_self_talk$captureSynthesisBytes(byte[] data, CallbackInfo ci) {
        PrebuiltAudioContext audio = this.maid_self_talk$audio;
        if (audio == null || audio.hasHitBytes()) {
            return;
        }
        audio.onSynthesisBytes(data);
        EntityMaid maid = ((TTSCallback) (Object) this).getMaid();
        if (maid != null && maid.level() instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            server.submit(() -> audio.trySettle(server));
        }
    }

    @Inject(method = "lambda$onSuccess$1", remap = false, at = @At("HEAD"), cancellable = true)
    private void maid_self_talk$guardTtsSuccessDelivery(CallbackInfo ci) {
        if (isInvalidated()) {
            // 清空作废：不发语音封包、不写旧聊天文本，只回收本条自己的等待气泡
            discardOwnWaitingBubble();
            ci.cancel();
            return;
        }
        PrebuiltAudioContext audio = this.maid_self_talk$audio;
        if (audio == null || audio.hasHitBytes()) {
            return;
        }
        // 结算兜底（幂等）：字节捕获任务已先入队，此处只是防止极端顺序下漏登记
        EntityMaid maid = ((TTSCallback) (Object) this).getMaid();
        if (!(maid.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        audio.trySettle(serverLevel.getServer());
        if (!maid_self_talk$isTtsActive(maid)) {
            // TTS 现势已关闭（发起时还开着）：不发音频包、不补合成，只把聊天文本补上屏
            ci.cancel();
            maid.getChatBubbleManager().addLLMChatText(this.maid_self_talk$chatText,
                    this.maid_self_talk$waitingChatBubbleId);
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

    /** TLM 的 TTS 现势判定（与 LLMCallback.onSuccess 的分支条件同口径） */
    @Unique
    private static boolean maid_self_talk$isTtsActive(EntityMaid maid) {
        return AIConfig.TTS_ENABLED.get() && maid.getAiChatManager().getTTSSite() != null
                && maid.getAiChatManager().getTTSSite().enabled();
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
