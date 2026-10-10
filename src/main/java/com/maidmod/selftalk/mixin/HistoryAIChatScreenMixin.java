package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.HistoryAIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.client.AutonomousChatHistoryView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 在原版聊天记录界面里合并展示独立档案（客户端）。
 * <p>
 * 时机选在 {@code transformMessage()} 的返回处（TAIL）：那时 TLM 已按自己的规则把玩家消息、
 * assistant 回复与工具名记录转成本地显示列表，这里只把未隐藏的独立记录按<b>持久顺序号</b>
 * 并进同一个显示列表（见 {@link AutonomousChatHistoryView#mergeInto}）。
 * <p>
 * 合并结果只写进界面自己的显示列表，<b>不写回</b> TLM 的 deque，也不改动摘要面板，
 * 更不会归档已被 TLM 压缩删除的普通对话。清空流程照旧走原版按钮与
 * {@code clearAllChatMemory}：存储侧钩子已推进隐藏边界，界面重开读到的就是清空后的可见集合，
 * 因此这里不新增按钮、不新增恢复入口。
 * <p>
 * 刷新时机保持原版开屏快照语义（档案随 AI 数据同步抵达客户端），不承诺界面持续打开时实时更新。
 */
@Mixin(HistoryAIChatScreen.class)
public abstract class HistoryAIChatScreenMixin {

    @Shadow
    private EntityMaid maid;

    /** 宿主界面自己的显示列表（TLM 在 {@code transformMessage} 里填好，这里只做就地合并） */
    @Shadow
    private List<LLMMessage> history;

    @Inject(method = "transformMessage", at = @At("TAIL"))
    private void maid_self_talk$mergeAutonomousHistory(CallbackInfo ci) {
        AutonomousChatHistoryView.mergeInto(maid, history);
    }
}
