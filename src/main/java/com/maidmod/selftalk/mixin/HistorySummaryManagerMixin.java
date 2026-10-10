package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.AutonomousChatHistoryMigration;
import com.maidmod.selftalk.CompressionHistoryPolicy;
import com.maidmod.selftalk.HistoryRetrievalCache;
import com.maidmod.selftalk.SelfTalkProvenance;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 历史压缩（TLM 摘要替换旧消息）时的两处接入：压缩材料来源口，以及压缩成功后的指纹清理。
 * <p>
 * 一、{@code buildSummaryRequest} 的入参处理处调用 {@link CompressionHistoryPolicy}：
 * 摘要<b>材料副本</b>从统一入口取，默认只用 TLM 原始快照（自话／欢迎语／互聊与工具过程
 * 已改存独立档案，不在 TLM 队列里）。必须分清两份数据——原始快照仍由 TLM 用于尾部一致性校验
 * 与成功后的删除依据，材料副本只用于构造摘要请求，两者绝不混用。
 * <p>
 * 二、注入点选 TAIL：{@code completeHistorySummary} 的所有提前返回路径
 * （压缩进行标志守卫、摘要空白、快照与历史不一致）都不会到达 TAIL，即指纹删除只在
 * 「pollLast 已真正删除历史消息」的成功路径上执行——判定始终与历史内容同步。
 * <p>
 * 该回调在 LLM 响应线程执行（TLM 异步摘要），而附件容器（AttachmentHolder）的
 * IdentityHashMap 非线程安全，故派发到服务端主线程删除（延迟一 tick 无害——
 * 被压缩的消息已出历史，指纹多留一 tick 只是短暂死条目）。
 * <p>
 * 另在 {@code tryScheduleHistorySummary} 开始处完成旧数据迁移：自动与手动两条压缩入口
 * 都先看到已清理的 TLM 历史（幂等；迁移只搬旧指纹明确命中的存量自话）。
 */
@Mixin(HistorySummaryManager.class)
public abstract class HistorySummaryManagerMixin {

    /**
     * 压缩材料副本的唯一生成口。
     * <p>
     * 只改{@code buildSummaryRequest} 收到的材料，不动原始快照：
     * {@code matchesTailSnapshot} 与成功后的 {@code pollLast} 都仍以 TLM 自己的快照为准，
     * 因此即使将来策略往材料里加内容，也不会让 TLM 删错历史。
     */
    @ModifyVariable(method = "buildSummaryRequest", at = @At("HEAD"), argsOnly = true)
    private List<LLMMessage> maid_self_talk$summaryMaterial(List<LLMMessage> snapshot) {
        MaidAIChatManager manager = ((HistorySummaryManagerAccessor) (Object) this).maid_self_talk$getChatManager();
        return CompressionHistoryPolicy.summaryMaterial(manager == null ? null : manager.getMaid(), snapshot);
    }

    /** 压缩调度前完成旧数据迁移：自动与手动压缩共用同一幂等入口 */
    @Inject(method = "tryScheduleHistorySummary", at = @At("HEAD"))
    private void maid_self_talk$migrateBeforeSummary(Runnable afterSummary, CallbackInfoReturnable<Boolean> cir) {
        MaidAIChatManager manager = ((HistorySummaryManagerAccessor) (Object) this).maid_self_talk$getChatManager();
        if (manager != null) {
            AutonomousChatHistoryMigration.ensureMigratedOnServerThread(manager.getMaid());
        }
    }

    @Inject(method = "completeHistorySummary", at = @At("TAIL"))
    private void maid_self_talk$removeCompressedProvenance(String summary, List<LLMMessage> snapshot,
                                                           CallbackInfoReturnable<Boolean> cir) {
        MaidAIChatManager manager = ((HistorySummaryManagerAccessor) (Object) this).maid_self_talk$getChatManager();
        EntityMaid maid = manager.getMaid();
        if (maid == null || !(maid.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.getServer().execute(() -> {
            SelfTalkProvenance.removeByMessages(maid, snapshot);
            // 压缩把旧消息换成了摘要：可检索对话随之变化，只做失效标记。
            // 独立档案与有效自话上下文不在这里清理——它们随 AI 数据另行保存，不受压缩影响
            HistoryRetrievalCache.invalidate(maid);
        });
    }
}
