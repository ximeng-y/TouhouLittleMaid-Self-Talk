package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 撬开 HistorySummaryManager 的 private chatManager 字段（TLM 类 prod 不混淆，字段名稳定）。
 */
@Mixin(HistorySummaryManager.class)
public interface HistorySummaryManagerAccessor {

    @Accessor("chatManager")
    MaidAIChatManager maid_self_talk$getChatManager();
}
