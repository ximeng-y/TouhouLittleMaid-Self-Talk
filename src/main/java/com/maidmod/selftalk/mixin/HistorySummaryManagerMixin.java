package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.SelfTalkProvenance;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 历史压缩（TLM 摘要替换旧消息）时同步删除对应的自话指纹。
 * <p>
 * 注入点选 TAIL：{@code completeHistorySummary} 的所有提前返回路径
 * （压缩进行标志守卫、摘要空白、快照与历史不一致）都不会到达 TAIL，即指纹删除只在
 * 「pollLast 已真正删除历史消息」的成功路径上执行——判定始终与历史内容同步。
 * <p>
 * 该回调在 LLM 响应线程执行（TLM 异步摘要），而附件容器（AttachmentHolder）的
 * IdentityHashMap 非线程安全，故派发到服务端主线程删除（延迟一 tick 无害——
 * 被压缩的消息已出历史，指纹多留一 tick 只是短暂死条目）。
 */
@Mixin(HistorySummaryManager.class)
public abstract class HistorySummaryManagerMixin {

    @Inject(method = "completeHistorySummary", at = @At("TAIL"))
    private void maid_self_talk$removeCompressedProvenance(String summary, List<LLMMessage> snapshot,
                                                           CallbackInfoReturnable<Boolean> cir) {
        MaidAIChatManager manager = ((HistorySummaryManagerAccessor) (Object) this).maid_self_talk$getChatManager();
        EntityMaid maid = manager.getMaid();
        if (maid == null || !(maid.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        serverLevel.getServer().execute(() -> SelfTalkProvenance.removeByMessages(maid, snapshot));
    }
}
