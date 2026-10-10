package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

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
 * 预留本入口是为后续可能的「是否把来源重新纳入压缩材料」配置：届时只改这里，
 * 不改 TLM 的删除依据。当前不开放任一来源配置，不实现「重新纳入自话／互聊」的选项。
 */
public final class CompressionHistoryPolicy {

    private CompressionHistoryPolicy() {
    }

    /**
     * 生成本次摘要使用的消息材料副本。
     *
     * @param maid    所属女仆（当前策略不使用；供后续按来源筛选或按女仆取独立档案）
     * @param tlmSnapshot TLM 取出的原始快照（最旧端到新端），<b>只读</b>
     * @return 供构造摘要请求使用的材料副本；默认与原始快照内容相同、互不影响
     */
    public static List<LLMMessage> summaryMaterial(EntityMaid maid, List<LLMMessage> tlmSnapshot) {
        if (tlmSnapshot == null || tlmSnapshot.isEmpty()) {
            return List.of();
        }
        // 默认策略：只用 TLM 原始快照。返回不可变副本，调用方无法借此改回原始快照
        return List.copyOf(tlmSnapshot);
    }
}
