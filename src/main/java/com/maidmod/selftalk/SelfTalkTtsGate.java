package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

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
