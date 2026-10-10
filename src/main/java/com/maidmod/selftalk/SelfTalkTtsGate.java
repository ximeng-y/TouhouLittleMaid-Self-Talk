package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自话／欢迎语的清空令牌中转站：把请求令牌传进 TLM 在 {@code onSuccess} 期间新建的 TTS 回调。
 * <p>
 * TLM 的 {@code LLMCallback.onSuccess} 只<b>发起</b> TTS——远程 TTS 会新建一个不携带本请求任何信息的
 * {@code TTSCallback} 并开始一次网络往返，真正向主人交付（语音封包、聊天文本、失败红字）发生在
 * <b>之后</b>。清空恰好落在「发起」与「交付」之间时（「回复已返回并开始合成 → 玩家清空 →
 * 合成结果到达」），裸回调会把清空前的内容照常播报与上屏，违背清空作废语义
 * （见 {@link SelfTalkRequestToken}）。
 * <p>
 * 传递方式：自话／欢迎语在调用父类 {@code onSuccess} 的这段<b>同步区间</b>内登记本请求令牌
 * （{@link #runWith}），TLM 在该区间内构造 {@code TTSCallback} 时由 {@code SelfTalkTtsMixin}
 * 取走令牌（{@link #currentToken}），交给 {@link SelfTalkTtsCallback} 在交付的当下判定。
 * <p>
 * 用 {@link ThreadLocal} 而非静态字段：登记与取用发生在同一线程的同一段调用栈上
 * （{@code onSuccess → tts → new TTSCallback}），静态字段会让这段区间内其它女仆、
 * 其它请求的 TTS 取到不属于自己的令牌。
 * <p>
 * <b>普通玩家聊天与互聊不经过 {@link #runWith}</b>：没有登记、令牌为 null，
 * 注入点原样使用 TLM 自己的回调，行为与未安装本 mod 时一致。
 */
public final class SelfTalkTtsGate {

    private static final ThreadLocal<SelfTalkRequestToken> CURRENT = new ThreadLocal<>();

    /**
     * 系统 TTS 延迟任务的令牌中转：键为（女仆实体 id + 等待气泡 id），值为发起请求的令牌。
     * <p>
     * 系统 TTS（{@code TTSSystemServices} 分支）不新建 {@code TTSCallback}，而是在
     * {@code MaidAIChatManager.onPlaySoundLocal} 内另投递一个主线程任务去发声并上屏；
     * 该任务执行时 {@link #CURRENT} 这条登记早已随 {@link #runWith} 结束而清除，无法再读到令牌。
     * 因此在 {@code onPlaySoundLocal} 的同步区间（仍在 {@code runWith} 内）把令牌按本请求的
     * 气泡 id 寄存，延迟任务执行时再按同一键取走并判失效。
     * <p>
     * {@code MaidAIChatManager} 是每只女仆共享、跨请求复用的对象，不能用实例字段寄存
     * （并发请求会相互覆盖）；气泡 id 由 {@code System.currentTimeMillis()} 生成、仅在单只女仆内近似唯一，
     * 故键里带上女仆 id 以免跨女仆同毫秒碰撞。每次 arm 必有对应的 consume（延迟任务无条件投递），不积累。
     */
    private static final Map<SystemTtsKey, SelfTalkRequestToken> SYSTEM_PENDING = new ConcurrentHashMap<>();

    private record SystemTtsKey(int maidId, long waitingChatBubbleId) {
    }

    private SelfTalkTtsGate() {
    }

    /**
     * 在发起 TTS 的同步区间内登记本次请求的令牌，区间结束后恢复原值（可重入嵌套）。
     * <p>
     * 令牌为 null（普通玩家聊天等）时直接执行，不建立登记。
     */
    public static void runWith(SelfTalkRequestToken token, Runnable action) {
        if (token == null) {
            action.run();
            return;
        }
        SelfTalkRequestToken previous = CURRENT.get();
        CURRENT.set(token);
        try {
            action.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 当前线程上正在发起 TTS 的请求令牌；不在登记区间内（玩家聊天等）返回 null */
    public static SelfTalkRequestToken currentToken() {
        return CURRENT.get();
    }

    /**
     * 系统 TTS 发起时（{@code onPlaySoundLocal} HEAD，仍在 {@link #runWith} 同步区间内）寄存本请求令牌，
     * 供随后投递的主线程发声任务取用。
     * <p>
     * 令牌为 null（普通玩家聊天等未登记路径）或气泡 id 为 0 时不寄存——延迟任务届时取不到令牌，
     * 按「无令牌」正常交付，与未安装本 mod 时一致。
     */
    public static void armSystemTts(EntityMaid maid, long waitingChatBubbleId) {
        SelfTalkRequestToken token = CURRENT.get();
        if (token == null || maid == null || waitingChatBubbleId == 0) {
            return;
        }
        SYSTEM_PENDING.put(new SystemTtsKey(maid.getId(), waitingChatBubbleId), token);
    }

    /**
     * 系统 TTS 的主线程发声任务执行时（延迟任务 HEAD）取走并消费本请求令牌，判断是否已被清空作废。
     * <p>
     * 取走即移除（一次消费，不积累）；未寄存（玩家聊天）或令牌仍有效时返回 false，按原样交付。
     */
    public static boolean consumeSystemTtsInvalidated(EntityMaid maid, long waitingChatBubbleId) {
        if (maid == null || waitingChatBubbleId == 0) {
            return false;
        }
        SelfTalkRequestToken token = SYSTEM_PENDING.remove(new SystemTtsKey(maid.getId(), waitingChatBubbleId));
        return token != null && !token.isValid();
    }

    /**
     * 清掉某个请求自己的等待气泡（按捕获的 id 精准删除，绝不误删其它请求的气泡）。
     * <p>
     * 调用点在 TTS 响应线程，而气泡管理属于服务端主线程，因此统一转投主线程执行；
     * 实体已不在服务端世界时直接放弃——气泡随实体一并消失。
     */
    public static void discardWaitingBubble(EntityMaid maid, long waitingChatBubbleId) {
        if (maid == null || waitingChatBubbleId == 0) {
            return;
        }
        if (!(maid.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.getServer().submit(() -> {
            if (maid.isAlive()) {
                maid.getChatBubbleManager().removeChatBubble(waitingChatBubbleId);
            }
        });
    }
}
