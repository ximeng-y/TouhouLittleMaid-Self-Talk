package com.maidmod.selftalk.citest.mixin;

import com.maidmod.selftalk.citest.CiClientProbe;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 客户端聊天栏观测：只记录最终显示的文本，用于验证回复确实上屏 */
@Mixin(ChatComponent.class)
public abstract class CiChatComponentMixin {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("TAIL"))
    private void maid_self_talk_ci$captureChat(Component message, CallbackInfo ci) {
        CiClientProbe.chat(message.getString(), "chat_component");
    }
}
