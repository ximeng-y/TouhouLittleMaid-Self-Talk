package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.AutonomousChatHistory;
import com.maidmod.selftalk.AutonomousChatHistoryHost;
import com.maidmod.selftalk.AutonomousChatRecord;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 独立档案在历史界面上的展示层（纯客户端）。
 * <p>
 * 档案本体随 TLM 的开屏同步（{@code writeToTag → SyncMaidAIDataPacket → readFromTag}）到达客户端，
 * 这里只做「取未隐藏记录 → 转成界面消息 → 与 TLM 普通历史按发生顺序归并」的映射：
 * <ul>
 *   <li>排序用<b>持久顺序号</b>，不用 {@code gameTime}——各维度 {@code gameTime} 独立计数，
 *       不能跨维度比较（{@code gameTime} 只用于把界面消息对回它来源的那条 TLM 历史）；</li>
 *   <li>正文只取聊天文本：{@link AutonomousChatRecord#chatText()} 展示上不含 TTS 段、段标签与内部 UUID，
 *       这里再兜底过一次 {@link ResponseChat}，以免历史遗留记录带着完整 {@code chatText---ttsText} 形态；</li>
 *   <li>来源标签（自话／欢迎语／互聊 + 实际发言女仆名）只在这里拼接，<b>绝不</b>写回任何存储、
 *       TTS 文本或发送给模型的原话。</li>
 * </ul>
 * 手动清空过的记录（顺序号小于隐藏边界）已被 {@link AutonomousChatHistory#visibleArchive()} 过滤。
 * 合并结果只写进界面自己的显示列表，不写回 TLM 的 deque，也不改动摘要面板。
 */
public final class AutonomousChatHistoryView {

    private AutonomousChatHistoryView() {
    }

    /**
     * 把未隐藏的独立档案按持久顺序号并进界面显示列表（就地改写在 {@code display} 上）。
     * <p>
     * 界面列表与 TLM 历史 deque 都按「旧 → 新」排列。界面消息是 TLM 在 {@code transformMessage}
     * 里按自己的规则<b>重新构造</b>的：玩家消息直接用历史对象，assistant 回复与工具名记录是新对象，
     * 后者无法按对象身份回查顺序号。因此这里把每条界面消息对回它来源的那条 deque 消息
     * （沿用 {@code gameTime}，并逐个向前消耗，次序不乱），再取那条消息的顺序号。
     * 对不上的界面消息（理论上不该有）按原位置保留，不参与归并。
     */
    public static void mergeInto(EntityMaid maid, List<LLMMessage> display) {
        if (maid == null || display == null || display.isEmpty()) {
            return;
        }
        AutonomousChatHistory archive = AutonomousChatHistoryHost.of(maid);
        if (archive == null) {
            return;
        }
        List<AutonomousChatRecord> records = archive.visibleArchive();
        if (records.isEmpty()) {
            return;
        }
        List<Long> displaySeqs = alignSeqs(display, dequeOf(maid), archive);
        List<Entry> entries = new ArrayList<>(records.size());
        for (AutonomousChatRecord record : records) {
            String text = displayText(record);
            if (StringUtils.isBlank(text)) {
                continue;
            }
            entries.add(new Entry(new LLMMessage(Role.ASSISTANT, label(record, text), record.gameTime(),
                    null, null), record.seq()));
        }
        if (entries.isEmpty()) {
            return;
        }
        List<LLMMessage> merged = new ArrayList<>(display.size() + entries.size());
        int cursor = 0;
        for (int i = 0; i < display.size(); i++) {
            // 对不上顺序号的界面消息（理论上不该有）当作无穷新：独立记录一律排在它之前，
            // 至少保证「顺序号可比较的那部分」次序正确
            long boundary = displaySeqs.get(i) == null ? Long.MAX_VALUE : displaySeqs.get(i);
            while (cursor < entries.size() && entries.get(cursor).seq() <= boundary) {
                merged.add(entries.get(cursor++).displayMessage());
            }
            merged.add(display.get(i));
        }
        while (cursor < entries.size()) {
            merged.add(entries.get(cursor++).displayMessage());
        }
        display.clear();
        display.addAll(merged);
    }

    /**
     * 把每条界面消息对回其来源的 TLM 历史消息并取其顺序号；对不上时为 null。
     * <p>
     * 游标只向前推进：界面列表是历史 deque 的保序投影（被杀掉的消息只被跳过），
     * 因此「当前游标之后第一条同角色、同 {@code gameTime} 的历史消息」就是它的来源。
     * 角色按界面形态判定：工具名记录来自带 {@code toolCalls} 的 assistant 消息，
     * 普通回复来自不带 {@code toolCalls} 的 assistant 消息。
     */
    private static List<Long> alignSeqs(List<LLMMessage> display, List<LLMMessage> dequeOldToNew,
                                        AutonomousChatHistory archive) {
        List<Long> seqs = new ArrayList<>(display.size());
        int cursor = 0;
        for (LLMMessage message : display) {
            Long found = null;
            int scan = cursor;
            while (scan < dequeOldToNew.size()) {
                LLMMessage candidate = dequeOldToNew.get(scan);
                if (matches(message, candidate)) {
                    found = archive.seqOf(candidate);
                    cursor = scan + 1;
                    break;
                }
                scan++;
            }
            seqs.add(found);
        }
        return seqs;
    }

    /** 界面消息是否由该历史消息转换而来 */
    private static boolean matches(LLMMessage display, LLMMessage candidate) {
        if (display.gameTime() != candidate.gameTime()) {
            return false;
        }
        boolean candidateHasTools = candidate.toolCalls() != null && !candidate.toolCalls().isEmpty();
        return switch (display.role()) {
            case USER -> candidate.role() == Role.USER;
            case TOOL -> candidate.role() == Role.ASSISTANT && candidateHasTools;
            case ASSISTANT -> candidate.role() == Role.ASSISTANT && !candidateHasTools;
            default -> false;
        };
    }

    /** 该女仆的 TLM 历史（旧到新）；AI 数据缺失时为空列表 */
    private static List<LLMMessage> dequeOf(EntityMaid maid) {
        var chatManager = maid.getAiChatManager();
        if (chatManager == null) {
            return List.of();
        }
        Deque<LLMMessage> deque = chatManager.getHistory().getDeque();
        List<LLMMessage> ordered = new ArrayList<>(deque.size());
        deque.descendingIterator().forEachRemaining(ordered::add);
        return ordered;
    }

    /** 一条待展示的界面消息连同它在女仆视角下的持久顺序号 */
    private record Entry(LLMMessage displayMessage, long seq) {
    }

    /**
     * 界面正文：来源标签 + 聊天文本。
     * <p>
     * 形如 {@code [自话] 内容}、{@code [互聊 · 名字] 内容}、{@code [欢迎语] 内容}。
     * 女仆名称为空（无命名牌且非 TLM 内置模型）时只显示来源，不留空括号。
     */
    private static String label(AutonomousChatRecord record, String chatText) {
        String key = switch (record.source()) {
            case SELF_TALK -> "chat.maid_self_talk.history.source.self_talk";
            case WELCOME -> "chat.maid_self_talk.history.source.welcome";
            case INTER_CHAT -> "chat.maid_self_talk.history.source.inter_chat";
        };
        // 标签只用本 mod 自己的语言键（不改动 TLM 已有文案）
        String source = Component.translatable(key).getString();
        String name = record.speakerName() == null ? "" : record.speakerName().trim();
        if (record.source() == AutonomousChatRecord.Source.INTER_CHAT && !name.isEmpty()) {
            return Component.translatable("chat.maid_self_talk.history.entry.inter_chat", source, name, chatText)
                    .getString();
        }
        return Component.translatable("chat.maid_self_talk.history.entry", source, chatText).getString();
    }

    /** 该记录的展示正文（剥掉 TTS 段的聊天文本）；无可用正文时为空串 */
    public static String displayText(AutonomousChatRecord record) {
        String raw = record.chatText();
        if (StringUtils.isBlank(raw)) {
            raw = record.message();
        }
        if (StringUtils.isBlank(raw)) {
            return StringUtils.EMPTY;
        }
        // 历史遗留记录可能带着完整的 ResponseChat 形态（chatText---ttsText），这里只取聊天段
        return new ResponseChat(raw).getChatText();
    }
}
