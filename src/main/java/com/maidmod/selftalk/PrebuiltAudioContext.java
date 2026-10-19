package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;

/**
 * 预制压缩语音缓存的单请求上下文：与 {@link SelfTalkTtsGate} 的清空令牌并行传递
 * （经 {@link SelfTalkTtsGate#runWith(SelfTalkRequestToken, PrebuiltAudioContext, Runnable)} 登记），
 * 冻结本次请求的条目引用、声音标识、完整目标语言、实际 TTS 文本与缓存命中字节。
 * <p>
 * 两种来源：
 * <ul>
 *   <li><b>复用命中</b>（{@link MaidSelfTalkService#deliverReused} 查得缓存音频）：
 *       携带命中字节，{@code MaidAIChatManager.tts} 入口的 mixin 直接按缓存播放并取消供应商调用，
 *       全程不再保存（缓存播放不重复入库）；</li>
 *   <li><b>捕获</b>（复用未命中继续真实生成，或真实生成链入库）：条目身份由
 *       {@code appendEntry} 的实际接收结果回填（UUID 冲突重随机后的最终身份），
 *       声音标识在 {@code tts} 入口按实际发起合成的站点冻结，合成字节由 TTS 回调补上；
 *       「回调字节先到」与「条目接收结果先到」两种顺序都支持，双方齐备且令牌有效时
 *       在主线程一次性登记待保存（幂等），入库失败（条目被拒）绝不产生孤立音频。</li>
 * </ul>
 * 状态机全程同一把锁（每请求一个实例、低频访问，不做无锁优化）；
 * 登记待保存只发生在服务端主线程的 {@link #trySettle}，字节捕获可来自 TTS 响应线程。
 * 普通玩家聊天、女仆互聊、排除供应商（Player2／系统 TTS）不建立本上下文，
 * 对应路径行为与未安装本 mod 时完全一致。
 */
public final class PrebuiltAudioContext {

    /** 生命周期令牌：清空作废后迟到的字节与接收结果一律静默放弃 */
    private final SelfTalkRequestToken token;
    /** 触发时冻结的条目定位凭据：身份指纹与来源（welcome / self-talk） */
    private final String fingerprint;
    private final String source;
    /**
     * 条目最终身份：复用命中即被命中的条目（创建即知）；真实生成由
     * {@link #onEntryAccepted} 用 appendEntry 的实际接收结果回填（null = 尚未接收或入库失败）。
     */
    private SelfTalkReuseStore.PrebuiltEntry entry;
    /** custom 条目源 Markdown 相对路径（定位同目录人工音频与自动音频镜像目录）；机器条目为 null */
    @Nullable
    private final String customRelPath;

    // ===== 缓存命中（复用命中查得音频时） =====
    private byte[] hitBytes;
    /** 命中自动音频时 JSON 提供的 TTS 文本（保证与音频一致）；人工音频无 JSON，为 null（用条目 TTS 段） */
    @Nullable
    private final String hitTtsText;

    // ===== 真实合成捕获 =====
    private boolean identityCaptured;
    /** 实际声音目录候选（可读名 + hash-only 兜底，均带完整声音键的 12 位前缀） */
    @Nullable
    private VoiceDirNames.Names voiceDirs;
    /** 实际发起合成时的目标 TTS 语言原文（如 zh_cn），写入 JSON */
    private String ttsLanguage;
    /** 实际送合成的 TTS 文本（JSON 内容与它一致） */
    private String ttsText;
    private byte[] synthesizedBytes;
    /** 终态：已登记待保存，或明确放弃（作废／格式不符／条目被拒）后不再登记 */
    private boolean settled;

    private PrebuiltAudioContext(SelfTalkRequestToken token, String fingerprint, String source,
                                 SelfTalkReuseStore.PrebuiltEntry entry,
                                 @Nullable String customRelPath, byte[] hitBytes,
                                 @Nullable String hitTtsText) {
        this.token = token;
        this.fingerprint = fingerprint;
        this.source = source;
        this.entry = entry;
        this.customRelPath = customRelPath;
        this.hitBytes = hitBytes;
        this.hitTtsText = hitTtsText;
    }

    /** 复用命中且查得缓存音频：直接按缓存播放，不再捕获与保存 */
    static PrebuiltAudioContext forHit(SelfTalkRequestToken token, String fingerprint, String source,
                                       SelfTalkReuseStore.PrebuiltEntry entry, byte[] hitBytes,
                                       @Nullable String hitTtsText) {
        return new PrebuiltAudioContext(token, fingerprint, source, entry, null, hitBytes, hitTtsText);
    }

    /** 捕获上下文：条目身份可为 null（真实生成，等 appendEntry 回填），custom 条目带源文件相对路径 */
    static PrebuiltAudioContext forCapture(SelfTalkRequestToken token, String fingerprint, String source,
                                           SelfTalkReuseStore.PrebuiltEntry entry,
                                           @Nullable String customRelPath) {
        return new PrebuiltAudioContext(token, fingerprint, source, entry, customRelPath, null, null);
    }

    /** 当前线程登记区间内的上下文（不在登记区间返回 null，普通聊天等路径照常） */
    @Nullable
    public static PrebuiltAudioContext current() {
        return SelfTalkTtsGate.currentAudio();
    }

    /** 是否携带缓存命中字节（mixin 据此在 tts 入口改走缓存播放并取消供应商调用） */
    public synchronized boolean hasHitBytes() {
        return hitBytes != null;
    }

    /** 缓存命中字节（仅供确认命中后的播放方读取） */
    public synchronized byte[] hitBytes() {
        return hitBytes;
    }

    /** 命中交付的 TTS 文本：自动音频用 JSON 冻结值，人工音频回退条目 TTS 段 */
    @Nullable
    public synchronized String deliveryTtsText() {
        if (hitTtsText != null) {
            return hitTtsText;
        }
        return entry != null ? entry.ttsText : null;
    }

    /** 本请求令牌是否仍然有效（播放交付前重判清空作废） */
    public boolean isTokenValid() {
        return token == null || token.isValid();
    }

    /**
     * tts 入口冻结交付身份（mixin 在供应商调用发起前调用，主线程）：
     * 声音目录名按<b>实际发起合成</b>的站点与音色计算，语言与文本取当次调用实参；
     * 命中播放路径不会调用本方法。随后尝试结算（字节与条目可能都已就位）。
     */
    public synchronized void noteDeliveryIdentity(MinecraftServer server, TTSSite site, String modelId,
                                           String ttsLanguage, String ttsText) {
        if (hitBytes != null || settled || identityCaptured) {
            return;
        }
        this.voiceDirs = PrebuiltAudioStore.voiceDirsOf(site, modelId);
        this.ttsLanguage = ttsLanguage;
        this.ttsText = ttsText;
        this.identityCaptured = true;
        if (this.voiceDirs == null) {
            // 摘要或目录名计算失败（理论不可达）：本上下文终止，不阻塞正常合成
            this.settled = true;
            return;
        }
        trySettle(server);
    }

    /** TTS 回调成功入口捕获合成字节（可能在响应线程，由 mixin 调用）；命中播放路径不捕获 */
    public synchronized void onSynthesisBytes(byte[] data) {
        if (hitBytes != null || settled || data == null || data.length == 0) {
            return;
        }
        this.synthesizedBytes = data;
    }

    /**
     * 真实生成链的条目接收结果回填（服务端主线程，{@code maybeAppendPrebuilt}）：
     * {@code accepted} 为 null（UUID 冲突耗尽被拒）时本上下文作废，绝不登记孤立音频。
     */
    synchronized void onEntryAccepted(@Nullable SelfTalkReuseStore.PrebuiltEntry accepted) {
        if (hitBytes != null || settled) {
            return;
        }
        if (accepted == null) {
            // 入库失败：音频没有归属条目，直接终态（不写缓存、不报错——正常生成链照常）
            this.settled = true;
            return;
        }
        this.entry = accepted;
    }

    /**
     * 结算（服务端主线程，幂等）：令牌有效且条目身份、交付身份、合成字节齐备时，
     * 识别压缩格式并登记待保存；WAV／裸 Opus／未知格式不写缓存、直接终态；
     * 作废静默放弃。
     *
     * @return 本次会议是否到达终态并完成登记（重复调用返回 false）
     */
    public synchronized boolean trySettle(MinecraftServer server) {
        if (settled) {
            return false;
        }
        if (token != null && !token.isValid()) {
            // 清空作废：迟到的字节与接收结果一并静默回收
            settled = true;
            return false;
        }
        if (!identityCaptured || voiceDirs == null || synthesizedBytes == null || entry == null
                || ttsText == null || ttsText.isBlank() || ttsLanguage == null) {
            return false;
        }
        PrebuiltAudioStore.AudioFormat format = PrebuiltAudioStore.detect(synthesizedBytes);
        if (format == null) {
            // WAV、裸 Opus、未知容器：绝不通过改扩展名冒充压缩格式，也不反复重试
            settled = true;
            return false;
        }
        PrebuiltAudioStore.registerPending(server, fingerprint, source, entry,
                customRelPath, voiceDirs, ttsLanguage, ttsText, synthesizedBytes, format);
        settled = true;
        return true;
    }
}
