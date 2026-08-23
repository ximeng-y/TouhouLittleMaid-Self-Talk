package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.maidmod.selftalk.SelfTalkProvenance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 历史压缩（TLM 摘要替换旧消息）时同步删除对应的自话指纹。
 * <p>
 * 注入点选 TAIL：{@code completeHistorySummary} 的两处提前返回
 * （摘要空白、快照与历史不一致）都不会到达 TAIL，即指纹删除只在
 * 「pollLast 已真正删除历史消息」的成功路径上执行——判定始终与历史内容同步。
 * <p>
 * 该回调在 LLM 响应线程执行（TLM 异步摘要），指纹附件为并发集，跨线程读写安全。
 */
@Mixin(HistorySummaryManager.class)
public abstract class HistorySummaryManagerMixin {

    @Inject(method = "completeHistorySummary", at = @At("TAIL"))
    private void maid_self_talk$removeCompressedProvenance(String summary, List<LLMMessage> snapshot,
                                                           CallbackInfoReturnable<Boolean> cir) {
        MaidAIChatManager manager = ((HistorySummaryManagerAccessor) (Object) this).maid_self_talk$getChatManager();
        SelfTalkProvenance.removeByMessages(manager.getMaid(), snapshot);
    }
}
