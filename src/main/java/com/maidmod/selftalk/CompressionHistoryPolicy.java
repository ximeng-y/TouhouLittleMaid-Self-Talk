package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩输入来源策略（TLM 历史摘要的「摘要材料副本」唯一生成口）。
 * <p>
 * 必须区分两份数据：
 * <ul>
 *   <li><b>原始快照</b>：{@code HistorySummaryManager.snapshotOldestMessages} 取出的最旧消息，
 *       TLM 用它做尾部一致性校验（{@code matchesTailSnapshot}）并在成功后按条数
 *       {@code pollLast} 删除。<b>本类绝不修改它</b>，也不生成任何会被当成删除依据的列表；</li>
 *   <li><b>摘要材料副本</b>：本类输出，只用于构造摘要请求的 user 消息。</li>
 * </ul>
 * 自话、欢迎语、互聊及其工具过程都已改存独立档案、不进入 TLM 队列，因此默认策略就是
 * 「原样使用 TLM 原始快照」——独立档案、有效自话上下文与互聊窗口一律不参与摘要材料，
 * 也不为它们单独触发压缩（触发条件仍只看 TLM 普通历史条数与 token 用量，见 TLM 侧判定）。
 * <p>
 * 偷听聊天是另一套口径：它是「有效原文」而不是 TLM 历史，普通摘要看不到它。
 * 这里额外冻结一份本次摘要覆盖的偷听上下文（编号、store 与请求令牌），
 * 由 Manager 的注入点交给实际回调；只有原生摘要被接受后，这些编号才退出自动携带。
 */
public final class CompressionHistoryPolicy {

    private CompressionHistoryPolicy() {
    }

    /**
     * 本次摘要使用的消息材料与附带的偷听覆盖快照。
     *
     * @param material 摘要请求的消息材料副本（不可变）
     * @param context  偷听覆盖上下文；没有有效偷听原文时为 null
     */
    public record Result(List<LLMMessage> material, OverheardSummaryHost.Context context) {
    }

    /**
     * 生成本次摘要使用的消息材料副本与偷听覆盖上下文。
     *
     * @param maid        所属女仆
     * @param tlmSnapshot TLM 取出的原始快照（最旧端到新端），<b>只读</b>
     */
    public static Result prepare(EntityMaid maid, List<LLMMessage> tlmSnapshot) {
        List<LLMMessage> base = tlmSnapshot == null || tlmSnapshot.isEmpty()
                ? List.of() : List.copyOf(tlmSnapshot);
        OverheardSummaryHost.Context context = freezeOverheard(maid);
        if (context == null) {
            return new Result(base, null);
        }
        // 材料副本追加一段旁听聊天：只影响摘要请求，不改 TLM 原始快照与删除依据
        String block = OverheardChatContext.summaryMaterialBlock(context.store(), context.coveredIds(),
                context.chinese());
        if (block.isBlank()) {
            return new Result(base, context);
        }
        List<LLMMessage> material = new ArrayList<>(base.size() + 1);
        material.addAll(base);
        material.add(LLMMessage.userChat(maid, block));
        return new Result(List.copyOf(material), context);
    }

    /** 冻结本次摘要覆盖的偷听有效原文；没有有效原文或取不到世界时返回 null */
    private static OverheardSummaryHost.Context freezeOverheard(EntityMaid maid) {
        if (maid == null || maid.level().getServer() == null) {
            return null;
        }
        OverheardChatStore store = OverheardChatSavedData.storeOf(maid.level().getServer());
        if (store == null) {
            return null;
        }
        // 先确保状态存在再捕获令牌：令牌按引用共享世代计数器，不能先捕获再建状态
        SelfTalkRequestToken token = SelfTalkRequestToken.capture(maid.getId());
        if (!token.isValid()) {
            return null;
        }
        List<OverheardChatStore.Entry> effective = store.readEffectiveMessages(maid.getUUID());
        if (effective.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>(effective.size());
        for (OverheardChatStore.Entry entry : effective) {
            ids.add(entry.id());
        }
        String chatLanguage = maid.getAiChatManager() == null ? null
                : maid.getAiChatManager().chatLanguage;
        boolean chinese = ContextLanguage.of(SelfTalkContexts.sanitizeLanguage(
                chatLanguage == null || chatLanguage.isBlank()
                        ? Config.SELF_TALK_LANGUAGE.get() : chatLanguage)).isZh();
        return new OverheardSummaryHost.Context(maid.getUUID(), token, store, List.copyOf(ids), chinese);
    }
}
