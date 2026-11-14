package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.maidmod.selftalk.AutonomousChatHistory;
import com.maidmod.selftalk.AutonomousChatHistoryHost;
import com.maidmod.selftalk.AutonomousChatHistoryMigration;
import com.maidmod.selftalk.CompressionHistoryPolicy;
import com.maidmod.selftalk.HistoryRetrievalCache;
import com.maidmod.selftalk.OverheardChatContext;
import com.maidmod.selftalk.OverheardSummaryHost;
import com.maidmod.selftalk.SelfTalkProvenance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 历史压缩（TLM 摘要替换旧消息）时的接入：压缩材料来源口、迁移入口、摘要 Prompt 追加，
 * 以及把本次偷听覆盖上下文移交给实际 callback。
 * <p>
 * 一、{@code buildSummaryRequest} 的入参处理处调用 {@link CompressionHistoryPolicy}：
 * 摘要<b>材料副本</b>从统一入口取，默认只用 TLM 原始快照。必须分清两份数据——原始快照仍由
 * TLM 用于尾部一致性校验与成功后的删除依据，材料副本只用于构造摘要请求，两者绝不混用。
 * 同一次调用里冻结「本次覆盖的偷听有效原文」，暂存到本 Manager 实例。
 * <p>
 * 二、压缩调度前完成旧数据迁移（{@code tryScheduleHistorySummary} 开始处）。
 * <p>
 * 三、{@code HistorySummaryPrompts.buildSummaryRequest} 的调用点用 {@code @WrapOperation}
 * 先执行原操作，再在返回的既有 Prompt 后追加偷听摘要规则；没有偷听材料时原样返回，
 * 不复制也不替换 TLM 整段 Prompt。
 * <p>
 * 四、{@code tryScheduleHistorySummary} 里对 {@code LLMClient.chat(LLMCallback)} 使用
 * {@code @WrapOperation}：把暂存上下文交给这次实际 {@code HistorySummaryCallback}，
 * 清掉暂存后再执行原调用一次。同步异常时释放暂存并保留原异常行为；方法 RETURN 清理未移交的暂存。
 * <p>
 * 五、注入点选 {@code completeHistorySummary} 的 TAIL：所有提前返回路径都不会到达 TAIL，
 * 即指纹删除只在「pollLast 已真正删除历史消息」的成功路径上执行。偷听有效原文的退出
 * 不挂在这里，而由 {@code HistorySummaryCallbackMixin} 在原生接受成功后安排。
 */
@Mixin(HistorySummaryManager.class)
public abstract class HistorySummaryManagerMixin {

    /** 本次摘要请求冻结的偷听覆盖上下文（由材料参数处理处填入，移交 callback 或方法 RETURN 时清空） */
    @Unique
    private OverheardSummaryHost.Context maid_self_talk$overheardContext;

    /**
     * 压缩材料副本的唯一生成口。
     * <p>
     * 只改 {@code buildSummaryRequest} 收到的材料，不动原始快照：
     * {@code matchesTailSnapshot} 与成功后的 {@code pollLast} 都仍以 TLM 自己的快照为准。
     */
    @ModifyVariable(method = "buildSummaryRequest", remap = false, at = @At("HEAD"), argsOnly = true)
    private List<LLMMessage> maid_self_talk$summaryMaterial(List<LLMMessage> snapshot) {
        MaidAIChatManager manager = ((HistorySummaryManagerAccessor) (Object) this).maid_self_talk$getChatManager();
        CompressionHistoryPolicy.Result result = CompressionHistoryPolicy.prepare(
                manager == null ? null : manager.getMaid(), snapshot);
        // 同一次调度只会调用一次 buildSummaryRequest；若有残留（上次异常路径），以本次为准
        this.maid_self_talk$overheardContext = result.context();
        return result.material();
    }

    /** 在既有摘要 Prompt 之后追加偷听来源要求；没有偷听材料时原样返回 */
    @WrapOperation(method = "buildSummaryRequest", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/summary/HistorySummaryPrompts;buildSummaryRequest(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))
    private String maid_self_talk$appendOverheardSummaryRules(String previousSummary, String historyBlock,
                                                             Operation<String> original) {
        String prompt = original.call(previousSummary, historyBlock);
        OverheardSummaryHost.Context context = this.maid_self_talk$overheardContext;
        if (context == null || prompt == null || prompt.isEmpty()) {
            return prompt;
        }
        return prompt + OverheardChatContext.summaryPromptSuffix(context.chinese());
    }

    /** 压缩调度前完成旧数据迁移：自动与手动压缩共用同一幂等入口 */
    @Inject(method = "tryScheduleHistorySummary", remap = false, at = @At("HEAD"))
    private void maid_self_talk$migrateBeforeSummary(Runnable afterSummary, CallbackInfoReturnable<Boolean> cir) {
        MaidAIChatManager manager = ((HistorySummaryManagerAccessor) (Object) this).maid_self_talk$getChatManager();
        if (manager != null) {
            AutonomousChatHistoryMigration.ensureMigratedOnServerThread(manager.getMaid());
        }
    }

    /**
     * 把本次覆盖上下文移交给实际摘要 callback。
     * <p>
     * 只有这里移交过的上下文才会在原生接受成功后让编号退出有效范围；
     * 移交后清掉暂存，确保一个上下文只对应一次真实请求。
     */
    @WrapOperation(method = "tryScheduleHistorySummary", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMClient;chat(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;)V"))
    private void maid_self_talk$handOverOverheardContext(LLMClient client, LLMCallback callback,
                                                         Operation<Void> original) {
        OverheardSummaryHost.Context context = this.maid_self_talk$overheardContext;
        this.maid_self_talk$overheardContext = null;
        if (context != null && callback instanceof OverheardSummaryHost host) {
            host.maid_self_talk$setOverheardSummary(context);
        }
        original.call(client, callback);
    }

    /** 未走到派发的提前返回：清掉暂存的覆盖上下文，不让它串到下一次调度 */
    @Inject(method = "tryScheduleHistorySummary", remap = false, at = @At("RETURN"))
    private void maid_self_talk$clearPendingOverheardContext(Runnable afterSummary,
                                                             CallbackInfoReturnable<Boolean> cir) {
        this.maid_self_talk$overheardContext = null;
    }

    @Inject(method = "completeHistorySummary", remap = false, at = @At("TAIL"))
    private void maid_self_talk$removeCompressedProvenance(String summary, List<LLMMessage> snapshot,
                                                           CallbackInfoReturnable<Boolean> cir) {
        MaidAIChatManager manager = ((HistorySummaryManagerAccessor) (Object) this).maid_self_talk$getChatManager();
        EntityMaid maid = manager.getMaid();
        SelfTalkProvenance.removeByMessages(maid, snapshot);
        if (maid != null && !maid.level().isClientSide()) {
            maid.level().getServer().execute(() -> {
                // 压缩已从队尾删掉旧消息：旁路顺序号对死消息的引用随之修剪，
                // 与检索缓存失效在同一主线程任务里完成（响应线程不直接动实体数据）
                AutonomousChatHistory history = AutonomousChatHistoryHost.of(maid);
                if (history != null) {
                    history.pruneOrderMetadata(manager.getHistory().getDeque());
                }
                HistoryRetrievalCache.invalidate(maid);
            });
        }
    }
}
