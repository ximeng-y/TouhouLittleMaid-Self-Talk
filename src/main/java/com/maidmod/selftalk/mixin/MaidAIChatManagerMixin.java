package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.maidmod.selftalk.MaidSelfTalkService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 钩玩家 chat 入口（MaidAIChatManager.normalChat，服务端主线程调用）。
 * <p>
 * 注入点在 normalChat 的 TAIL：normalChat 是 TLM 唯一创建玩家 chat 回调（LLMCallback）的路径，
 * 在此处标记"玩家 chat 进行中"可严格绑定标记与回调的生命周期——
 * chat() 中不产生回调的早退路径（LLM 关闭、token 超限、site 无效等）在 TAIL 前已 return，
 * 标记不会被错误置位。
 * <p>
 * 作用：
 * <ul>
 *   <li>标记该女仆"玩家 chat 进行中"（计数），自话触发时若命中则跳过（防交错写历史）；</li>
 *   <li>重置自话窗口（玩家 chat 后旧自话记录赦免保留在上下文中，保留条数计数重新开始）。</li>
 * </ul>
 */
@Mixin(MaidAIChatManager.class)
public abstract class MaidAIChatManagerMixin {

    @Inject(method = "normalChat", remap = false, at = @At("TAIL"))
    private void maid_self_talk$onPlayerChatStart(String message, List<LLMMessage> messages,
                                                  LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        MaidSelfTalkService.onPlayerChatStart(self.getMaid());
    }
}
