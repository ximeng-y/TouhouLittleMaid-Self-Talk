package com.maidmod.selftalk.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 玩家对话检索库的来源过滤与按轮分块（纯文本，无 Minecraft 依赖）。
 * <p>
 * 输入是女仆现存 TLM 历史的只读快照（从旧到新）。入库规则：
 * <ul>
 *   <li>普通 {@code USER} 玩家消息；</li>
 *   <li>对话轮内未被识别为自话／欢迎语的普通 {@code ASSISTANT} 回复。</li>
 * </ul>
 * 排除：{@code SYSTEM}（人设与摘要）、{@code TOOL}、携带 tool_calls 的工具过程消息、
 * 自话／欢迎语指纹命中的消息、互聊窗口与内部请求（二者本就不进 TLM 历史，天然不在快照内）。
 * <p>
 * 旧历史的兼容口径（不声称能百分之百排除自话）：
 * <ul>
 *   <li>以玩家 {@code USER} 开始的轮次可以入库；</li>
 *   <li>未被识别为自话的旧 assistant 留在该轮内；</li>
 *   <li>没有前导玩家消息的孤立 assistant <b>不</b>独立入库，也不建立新块。</li>
 * </ul>
 * 分块规则：每条普通玩家消息开始一个新块，到下一条玩家消息前结束；中间的工具消息跳过、
 * 不切断块；已知自话跳过、也不因此切块；连续两条玩家消息各自成块；只有玩家消息而无回复的块保留。
 * <b>不按字符数再次拆块，不截断长块</b>；内容相同但发生在不同轮次的记录不合并。
 */
public final class PlayerDialogueLibrary {

    private PlayerDialogueLibrary() {
    }

    /**
     * 来源过滤：从历史快照里挑出可检索的玩家消息与普通回复。
     * <p>
     * {@code selfTalkFingerprints} 为自话／欢迎语指纹集合
     * （见 {@link HistoryFingerprint}）；{@code legacyFingerprints} 为老会话快照集合——
     * 它标记的是「升级前就已存在、来源不可辨」的消息，因此对它们只用 role 规则，
     * 不额外丢弃（否则旧存档的整段对话都会消失）。
     */
    public static List<HistoryMessage> filterSearchable(List<HistoryMessage> snapshot,
                                                        Set<String> selfTalkFingerprints,
                                                        Set<String> legacyFingerprints) {
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }
        Set<String> selfTalk = selfTalkFingerprints == null ? Set.of() : selfTalkFingerprints;
        List<HistoryMessage> searchable = new ArrayList<>();
        boolean blockStarted = false;
        for (HistoryMessage message : snapshot) {
            if (message == null || message.isSystem()) {
                continue;
            }
            if (message.isUser()) {
                searchable.add(message);
                blockStarted = true;
                continue;
            }
            if (message.isTool() || message.hasToolCalls()) {
                continue;
            }
            if (!message.isAssistant()) {
                continue;
            }
            if (selfTalk.contains(message.fingerprint())) {
                // 已知自话／欢迎语：不入库，也不切断当前对话块
                continue;
            }
            if (!blockStarted) {
                // 孤立 assistant（没有前导玩家消息）：不独立入库，也不建立新块
                continue;
            }
            searchable.add(message);
        }
        return searchable;
    }

    /**
     * 按轮分块：每条普通玩家消息开始一个新块，到下一条玩家消息前结束。
     * <p>
     * 传入的必须是已过滤的可检索消息序列（{@link #filterSearchable} 的返回值）——
     * 分块只看玩家消息的位置，不再重复判定来源。
     */
    public static List<DialogueBlock> buildBlocks(List<HistoryMessage> searchable) {
        if (searchable == null || searchable.isEmpty()) {
            return List.of();
        }
        List<DialogueBlock> blocks = new ArrayList<>();
        int start = -1;
        List<HistoryMessage> current = new ArrayList<>();
        for (int i = 0; i < searchable.size(); i++) {
            HistoryMessage message = searchable.get(i);
            if (message.isUser()) {
                if (start >= 0 && !current.isEmpty()) {
                    blocks.add(new DialogueBlock(start, List.copyOf(current)));
                }
                start = i;
                current = new ArrayList<>();
            }
            if (start >= 0) {
                current.add(message);
            }
        }
        if (start >= 0 && !current.isEmpty()) {
            blocks.add(new DialogueBlock(start, List.copyOf(current)));
        }
        return List.copyOf(blocks);
    }

    /** 可检索序列的内容标识：内容变化（含同条数替换）必然改变该串，用于缓存来源比较 */
    public static List<String> snapshotTokens(List<HistoryMessage> searchable) {
        if (searchable == null || searchable.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>(searchable.size());
        for (HistoryMessage message : searchable) {
            tokens.add(message.fingerprint());
        }
        return List.copyOf(tokens);
    }
}
