package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.maidmod.selftalk.MaidSelfTalkService;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 钩玩家 chat 入口（MaidAIChatManager.chat，公开方法、服务端主线程调用）。
 * <p>
 * 作用：
 * <ul>
 *   <li>标记该女仆"玩家 chat 进行中"，自话触发时若命中则跳过（防交错写历史）；</li>
 *   <li>重置自话窗口（玩家 chat 后旧自话记录赦免保留在上下文中，保留条数计数重新开始）。</li>
 * </ul>
 */
@Mixin(MaidAIChatManager.class)
public abstract class MaidAIChatManagerMixin {

    @Inject(method = "chat", remap = false, at = @At("HEAD"))
    private void maid_self_talk$onPlayerChatStart(String message, ChatClientInfo clientInfo,
                                                  ServerPlayer sender, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        MaidSelfTalkService.onPlayerChatStart(self.getMaid());
    }
}
