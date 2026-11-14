package com.maidmod.selftalk;

import java.util.List;
import java.util.UUID;

/**
 * 实际摘要 callback 的附加元数据宿主（实现见 {@code HistorySummaryCallbackMixin}）。
 * <p>
 * 元数据只属于这一次真实回调，不建全局 callback 表：上下文随 callback 对象同生共死，
 * 原生摘要被接受后才按它让本次覆盖的有效原文退出自动携带。
 */
public interface OverheardSummaryHost {

    /** 绑定本次摘要覆盖的偷听上下文（由 Manager 在 {@code LLMClient.chat} 调用点移交） */
    void maid_self_talk$setOverheardSummary(Context context);

    /** 读取本次摘要覆盖的偷听上下文（清理入口用） */
    Context maid_self_talk$overheardSummary();

    /**
     * 一次摘要请求冻结的偷听覆盖范围。
     * <p>
     * 只保存编号与绑定，不复制正文：摘要请求发出后新增的编号不属于本次覆盖范围；
     * 正文在需要时按编号从 store 读取。
     */
    record Context(UUID maidUuid, SelfTalkRequestToken token, OverheardChatStore store,
                   List<Long> coveredIds, boolean chinese) {
    }
}
