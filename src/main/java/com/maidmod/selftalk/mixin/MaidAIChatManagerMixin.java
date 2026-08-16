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
 * 钩玩家 chat 真实派发入口（MaidAIChatManager.normalChat，private 方法、服务端主线程调用）。
 * <p>
 * 作用：
 * <ul>
 *   <li>标记该女仆"玩家 chat 进行中"（计数），自话触发时若命中则跳过（防交错写历史）；</li>
 *   <li>重置自话窗口（玩家 chat 后旧自话记录赦免保留在上下文中，保留条数计数重新开始）。</li>
 * </ul>
 * 注入点选在请求已提交之后（TAIL）：TLM {@code chat()} 有大量提前返回路径
 * （LLM 关闭/token 超限/site 缺失/密钥缺失/无人设/历史压缩）不产生任何回调，
 * 若在 chat 入口标记则标记可能永不清除、该女仆自话永久停摆；
 * {@code normalChat} 是唯一创建玩家 chat 回调（LLMCallback）的路径，
 * 且 client.chat 同步抛异常时 TAIL 同样不会执行——标记与回调生命周期严格绑定。
 */
@Mixin(MaidAIChatManager.class)
public abstract class MaidAIChatManagerMixin {

    @Inject(method = "normalChat", at = @At("TAIL"))
    private void maid_self_talk$onPlayerChatStart(String message, List<LLMMessage> messages,
                                                  LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        MaidSelfTalkService.onPlayerChatStart(self.getMaid());
    }
}
