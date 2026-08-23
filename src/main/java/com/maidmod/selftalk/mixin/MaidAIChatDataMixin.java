package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.SelfTalkProvenance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 历史清空（TLM 清理记忆）时同步清空自话指纹与 legacy 标记，避免判定的"幽灵来源"残留。
 * <p>
 * 防御：TLM 客户端历史界面（HistoryAIChatScreen）也会调用本方法，
 * 客户端实体不携带本 mod 的运行状态，直接跳过。
 */
@Mixin(MaidAIChatData.class)
public abstract class MaidAIChatDataMixin {

    @Inject(method = "clearAllChatMemory", at = @At("TAIL"))
    private void maid_self_talk$clearProvenance(CallbackInfo ci) {
        MaidAIChatData self = (MaidAIChatData) (Object) this;
        EntityMaid maid = self.getMaid();
        if (maid.level().isClientSide()) {
            return;
        }
        SelfTalkProvenance.clearAll(maid);
    }
}
