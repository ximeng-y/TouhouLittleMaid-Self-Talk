package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;

/**
 * 附加到原生等待气泡的轻量来源标记接口（由 {@code WaitingChatBubbleDataMixin} 注入实现）。
 * <p>
 * 只表达「这是自话/互聊的等待气泡」，不引入完整请求来源枚举或请求状态。
 * 客户端渲染过滤据此隐藏目标气泡；最终回答的文本气泡、欢迎语与普通 AI 聊天气泡不受影响。
 */
public interface SelfTalkWaitingBubbleData {

    /** 是否自话/互聊来源的等待气泡 */
    boolean isAutonomousWaiting();

    void setAutonomousWaiting(boolean value);

    /** 判断任意气泡数据是否为自话/互聊等待气泡（非等待对象恒为 false） */
    static boolean isAutonomous(IChatBubbleData data) {
        return data instanceof SelfTalkWaitingBubbleData marker && marker.isAutonomousWaiting();
    }
}
