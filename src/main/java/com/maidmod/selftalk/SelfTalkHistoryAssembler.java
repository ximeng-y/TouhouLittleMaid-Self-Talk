package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.history.DialogueBlock;
import com.maidmod.selftalk.history.HistoryFingerprint;
import com.maidmod.selftalk.history.HistoryMessage;
import com.maidmod.selftalk.history.PlayerDialogueLibrary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 历史上下文模式相关的消息列表处理：来源过滤视图、最近一条自话抽取、块级重排。
 * <p>
 * 本类<b>只筛选和重组本次请求的副本</b>，绝不删除或改写 TLM 保存的历史。
 * 纯逻辑（来源过滤、分块、打分）在 {@code history} 包，这里只做 {@code LLMMessage} 的映射与重排，
 * 因此本类不参与离线自检，检索内核的检查全部落在 {@code history} 包上。
 */
public final class SelfTalkHistoryAssembler {

    private SelfTalkHistoryAssembler() {
    }

    /**
     * 历史区结构：前导 SYSTEM 段（设定 + 可选摘要）、历史区、互聊窗口区（含 peerText）。
     * <p>
     * 与段标签包裹的输入结构一致：{@code [前导 SYSTEM, ...历史, ...窗口]}。
     */
    public record HistoryLayout(List<LLMMessage> systemPrefix, List<LLMMessage> history,
                                List<LLMMessage> window) {

        /** 前导 SYSTEM 条数（不含历史区内部的 SUMMARY SYSTEM——它在历史区游标之后） */
        public int systemCount() {
            return systemPrefix.size();
        }

        public int historyCount() {
            return history.size();
        }

        public int windowCount() {
            return window.size();
        }
    }

    /**
     * 本次请求副本中独立记录的来源索引。
     * <p>
     * {@code LLMMessage} 是 TLM 的 record，无法附加字段，因此来源信息按<b>对象身份</b>
     * 旁路登记：请求清洗只增删列表元素、不重建消息对象（{@code withContent} 除外，它只用于
     * 段标签包裹之后），重排也搬运同一批引用，因此登记在整个请求组装期间与消息一一对应。
     * 绝不按正文相等识别——相同正文、相同 tick 的不同消息（含双方重复说出同一句）必须各自归属。
     */
    public static final class SourceIndex {

        private final Map<LLMMessage, AutonomousChatRecord.Source> byMessage = new IdentityHashMap<>();

        /** 登记一条独立记录消息的来源（同一对象只可能有一种来源，重复登记以首次为准） */
        void mark(LLMMessage message, AutonomousChatRecord.Source source) {
            if (message != null && source != null) {
                byMessage.putIfAbsent(message, source);
            }
        }

        /** 该消息是否来自独立档案（自话／欢迎语／互聊）；不是则返回 null */
        public AutonomousChatRecord.Source sourceOf(LLMMessage message) {
            return message == null ? null : byMessage.get(message);
        }

        /** 本次请求副本里的独立记录条数（全量模式据此决定是否追加「最近一次自言自语」说明） */
        public int size() {
            return byMessage.size();
        }

        public boolean isEmpty() {
            return byMessage.isEmpty();
        }
    }

    /**
     * 把「有效自话上下文」按持久顺序号合并进 TLM 历史区，返回合并后的历史区副本，
     * 并把插入的每条消息登记进 {@code index}。
     * <p>
     * 独立记录与 TLM 历史消息共用同一套单调顺序号（见 {@link AutonomousChatHistory}），
     * 合并出的是<b>一条按真实发生顺序排列的对话流</b>——各维度 {@code gameTime} 独立计数，
     * 不能拿来跨维度排序。展示档案与有效上下文是两套生命周期，这里只读有效自话快照。
     * <p>
     * 返回的是本次请求的副本：既不写回 TLM deque，也不改动任何存储，输入列表原样不动
     * （调用方另有只含 TLM 玩家历史的输入，检索库与「最近玩家对话」仍以那份为准）。
     *
     * @param history TLM 历史区（旧到新，不含前导 SYSTEM）；可为空列表
     * @return 合并后的历史区；没有可合并内容时返回 {@code history} 本身
     */
    public static List<LLMMessage> mergeValidSelfTalk(EntityMaid maid, List<LLMMessage> history,
                                                      SourceIndex index) {
        AutonomousChatHistory archive = AutonomousChatHistoryHost.of(maid);
        if (archive == null) {
            return history;
        }
        // 允许空历史参与合并：新女仆已有欢迎语／自话、玩家却还没和她聊过时，
        // TLM 历史区为空而有效自话非空。此处若因 history 为空直接返回，
        // 默认全量模式就完全看不到仍有效的自话（精简／检索模式经 latestValidSelfTalk
        // 反而能看到最新一条），默认模式与其它模式的可见性自相矛盾。
        List<LLMMessage> base = history == null ? List.of() : history;
        List<AutonomousChatRecord> valid = archive.validSelfTalkSnapshot();
        if (valid.isEmpty()) {
            return base;
        }
        List<LLMMessage> merged = new ArrayList<>(base.size() + valid.size());
        int cursor = 0;
        for (LLMMessage message : base) {
            // 未登记顺序号的 TLM 消息（尚未补号的旧存档）视作最旧：排在全部独立记录之前
            Long seq = archive.seqOf(message);
            long boundary = seq == null ? 0L : seq;
            while (cursor < valid.size() && valid.get(cursor).seq() <= boundary) {
                merged.add(asRequestMessage(valid.get(cursor++), index));
            }
            merged.add(message);
        }
        // 顺序号大于全部 TLM 历史（最新发生的自话）：接在历史之后；历史为空时即全部有效自话
        while (cursor < valid.size()) {
            merged.add(asRequestMessage(valid.get(cursor++), index));
        }
        return List.copyOf(merged);
    }

    /**
     * 独立记录 → 本次请求副本里的 ASSISTANT 消息。
     * <p>
     * 正文取自 {@link AutonomousChatRecord#message()}（自话保留原 {@code ResponseChat.toString()} 形态，
     * 与玩家 chat 路径写入 TLM 历史的形态一致；互聊为纯聊天文本）。
     * 记录本身没有可读的来源标记，来源在同一批消息上按对象身份登记进 {@link SourceIndex}，
     * 供段标签使用——绝不按正文相等识别。
     */
    static LLMMessage asRequestMessage(AutonomousChatRecord record, SourceIndex index) {
        LLMMessage message = new LLMMessage(Role.ASSISTANT,
                record.message() == null ? "" : record.message(), record.gameTime(), null, null);
        if (index != null) {
            index.mark(message, record.source());
        }
        return message;
    }

    /**
     * 拆开一次已构建的完整消息列表（尚未附加本轮当前回合消息）。
     * <p>
     * 调用方持有 {@code historyCount} 与 {@code windowCount} 两个游标（构建时即知道），
     * 因此这里按游标切分，无需猜测结构。注意 {@code historyCount} 是「加入窗口前的
     * {@code messages.size()}」，<b>已包含前导 SYSTEM 段</b>，切分时必须扣除 {@code prefixEnd}，
     * 否则窗口区会被吞进历史区（互聊窗口与 peerText 全部丢失）。
     * 摘要 SYSTEM 位于历史区之内（TLM 的 buildMessage 把摘要紧接设定之后），所以它既不是前导段
     * 也不会被当作对话消息——{@link #toSearchableView} 会把它连同人设一起按 role 排除。
     */
    public static HistoryLayout split(List<LLMMessage> messages, int historyCount, int windowCount) {
        int total = messages.size();
        int prefixEnd = 0;
        while (prefixEnd < total && messages.get(prefixEnd).role() == Role.SYSTEM) {
            prefixEnd++;
        }
        int historyEnd = Math.min(prefixEnd + Math.max(0, historyCount - prefixEnd), total);
        int windowEnd = Math.min(historyEnd + Math.max(0, windowCount), total);
        return new HistoryLayout(
                List.copyOf(messages.subList(0, prefixEnd)),
                List.copyOf(messages.subList(prefixEnd, historyEnd)),
                List.copyOf(messages.subList(historyEnd, windowEnd)));
    }

    /**
     * 把 TLM 消息映射成检索库视图。
     * <p>
     * {@code toolCalls} 只用于「是否携带工具调用」这一个判定，其内容不进入检索库。
     */
    public static List<HistoryMessage> toSearchableView(List<LLMMessage> history) {
        List<HistoryMessage> view = new ArrayList<>(history.size());
        for (LLMMessage message : history) {
            view.add(new HistoryMessage(message.role().name(),
                    message.message() == null ? "" : message.message(),
                    message.gameTime(),
                    message.toolCalls() != null && !message.toolCalls().isEmpty()));
        }
        return view;
    }

    /**
     * 当前女仆的自话／欢迎语指纹集。
     * <p>
     * 读取口与段标签包裹同源（{@link SelfTalkProvenanceHost#maid_self_talk$selfFingerprints()}），
     * 因此「检索库排除自话」与「段标签把自话归入自话段」永远用同一份判定，
     * 不会出现一处认自话、另一处不认的分歧。
     */
    public static Set<String> selfTalkFingerprints(EntityMaid maid) {
        return ((SelfTalkProvenanceHost) maid).maid_self_talk$selfFingerprints();
    }

    /**
     * 可检索序列及其「回到原始历史区下标」的映射。
     * <p>
     * {@code searchable} 与 {@code indices} 逐项对应；检索块给出的是 {@code searchable} 内的下标，
     * 取原始消息时经 {@link #historyIndexAt(int)} 还原——两步分开是为了让纯文本内核
     * 完全不必知道 Minecraft 消息的存在。
     */
    public record SearchableIndices(List<HistoryMessage> searchable, int[] indices) {

        public int size() {
            return searchable.size();
        }

        public boolean isEmpty() {
            return searchable.isEmpty();
        }

        public HistoryMessage get(int searchableIndex) {
            return searchable.get(searchableIndex);
        }

        /** 可检索序列下标 → 原始历史区下标；越界返回 -1 */
        public int historyIndexAt(int searchableIndex) {
            return searchableIndex < 0 || searchableIndex >= indices.length ? -1 : indices[searchableIndex];
        }
    }

    /**
     * 来源过滤并保留「可检索序列下标 → 原始历史区下标」的映射。
     * <p>
     * 过滤本身委托给 {@link PlayerDialogueLibrary}（单一实现），本方法只负责还原下标：
     * 过滤结果按原顺序取自输入且不复制元素，因此可用一次前向扫描（引用相等）还原。
     */
    public static SearchableIndices filterSearchable(List<HistoryMessage> historyView,
                                                     Set<String> selfTalkFingerprints) {
        List<HistoryMessage> searchable =
                PlayerDialogueLibrary.filterSearchable(historyView, selfTalkFingerprints, Set.of());
        if (searchable.isEmpty()) {
            return new SearchableIndices(List.of(), new int[0]);
        }
        int[] indices = new int[searchable.size()];
        int cursor = 0;
        for (int i = 0; i < historyView.size() && cursor < searchable.size(); i++) {
            if (historyView.get(i) == searchable.get(cursor)) {
                indices[cursor] = i;
                cursor++;
            }
        }
        return new SearchableIndices(searchable, indices);
    }

    /**
     * 把召回的历史块按块边界重排进历史区，其余历史消息一律丢弃。
     * <p>
     * 只保留落在召回块内的消息并按原历史顺序输出——这正是「筛选后 rebuild」的语义，
     * 而不是对已经包裹好标签的完整列表做切片（那会产生不成对的段标签）。
     * <p>
     * 匹配用<b>下标</b>而不是内容指纹：两条内容与 gameTime 完全相同的消息会互相顶替，
     * 而块边界本来就是按位置定义的。
     */
    public static List<LLMMessage> selectRecalledMessages(List<LLMMessage> history,
                                                          SearchableIndices searchable,
                                                          List<DialogueBlock> blocks) {
        if (history.isEmpty() || searchable == null || searchable.isEmpty()
                || blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        Set<Integer> wanted = new HashSet<>();
        for (DialogueBlock block : blocks) {
            for (int i = block.startIndex(); i < block.endIndex() && i < searchable.size(); i++) {
                int historyIndex = searchable.historyIndexAt(i);
                if (historyIndex >= 0) {
                    wanted.add(historyIndex);
                }
            }
        }
        List<LLMMessage> result = new ArrayList<>(wanted.size());
        for (int i = 0; i < history.size(); i++) {
            if (wanted.contains(i)) {
                result.add(history.get(i));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 剔除已不属于当前历史的召回块。
     * <p>
     * 连续互聊的下一位发言属于新触发，但复用本链首次召回的块；这期间原存储可能已经删掉了
     * 那些消息（历史压缩、容量淘汰、玩家清空记忆）。<b>被删掉的块整体移出本链结果</b>——
     * 不重新规划、也不补足三个：索引缓存不能延长原始记录的寿命，
     * 留下半截对话块也会让模型看到一段没头没尾的上下文。
     *
     * @param recalledBlocks 本链首次召回得到的块（每项是一块的消息，按历史顺序）
     * @param alive          当前历史区（新触发重新拉取的那一份）
     * @return 仍然完整存在的块；全部失效时为空列表
     */
    public static List<List<LLMMessage>> retainAliveBlocks(List<List<LLMMessage>> recalledBlocks,
                                                           List<LLMMessage> alive) {
        if (recalledBlocks == null || recalledBlocks.isEmpty() || alive.isEmpty()) {
            return List.of();
        }
        Set<String> aliveKeys = new HashSet<>(alive.size() * 2);
        for (LLMMessage message : alive) {
            aliveKeys.add(fingerprintOf(message));
        }
        List<List<LLMMessage>> result = new ArrayList<>(recalledBlocks.size());
        for (List<LLMMessage> block : recalledBlocks) {
            if (block.isEmpty()) {
                continue;
            }
            boolean intact = true;
            for (LLMMessage message : block) {
                if (!aliveKeys.contains(fingerprintOf(message))) {
                    intact = false;
                    break;
                }
            }
            if (intact) {
                result.add(block);
            }
        }
        return List.copyOf(result);
    }

    /** 把「块 → 消息」的缓存展平为按块顺序、块内保序的一条消息列表 */
    public static List<LLMMessage> flattenBlocks(List<List<LLMMessage>> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<LLMMessage> result = new ArrayList<>();
        for (List<LLMMessage> block : blocks) {
            result.addAll(block);
        }
        return List.copyOf(result);
    }

    private static String fingerprintOf(LLMMessage message) {
        return HistoryFingerprint.of(message.role().name(),
                message.message() == null ? "" : message.message(), message.gameTime());
    }
}
