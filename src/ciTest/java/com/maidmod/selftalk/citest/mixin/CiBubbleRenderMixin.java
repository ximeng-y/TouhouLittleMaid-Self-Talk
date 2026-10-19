package com.maidmod.selftalk.citest.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.ChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.EntityGraphics;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.IChatBubbleRenderer;
import com.maidmod.selftalk.citest.CiClientProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 气泡渲染观测：只在方法正常返回时计数，因此产品 mixin 取消（本地开关隐藏）的等待气泡不会被计入。
 * 这样「渲染数」与「原始气泡集合」联合断言即可验证只隐藏目标气泡。
 */
@Mixin(ChatBubbleRenderer.class)
public abstract class CiBubbleRenderMixin {

    @Inject(method = "renderChatBubble", at = @At("RETURN"))
    private void maid_self_talk_ci$captureBubble(EntityGraphics graphics, IChatBubbleRenderer renderer,
                                                 IChatBubbleRenderer.Position position, int y,
                                                 CallbackInfo ci) {
        CiClientProbe.renderedBubble(renderer.getClass().getName());
    }
}
