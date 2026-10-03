package com.maidmod.selftalk.history;

import java.util.List;

/**
 * 一个玩家对话块：从一条普通玩家 {@code USER} 到下一条第玩家消息之前。
 * <p>
 * 块内保留原始发言顺序与正文（含女仆回复、含跨过工具消息后的最终回复）；
 * 只有玩家消息而无回复的块同样保留。位置字段用于「同分时以历史顺序较新的块优先」
 * 与「选出后按从旧到新输出」，不依赖任何时间戳——历史顺序即
 * {@code MaidAIChatManager.getMessages} 产出的顺序。
 *
 * @param startIndex 块内首条消息在可检索序列中的下标（含）
 * @param messages   块内保留的消息（玩家消息 + 未被识别为自话的普通回复）
 */
public record DialogueBlock(int startIndex, List<HistoryMessage> messages) {

    /** 块内全部正文按块内顺序拼接（供检索输出与词频统计共用同一份文本） */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (HistoryMessage message : messages) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(message.textOrEmpty());
        }
        return sb.toString();
    }

    /** 块结束下标（不含），用于与本轮捕获的历史消息区间做重叠判定 */
    public int endIndex() {
        return startIndex + messages.size();
    }
}
