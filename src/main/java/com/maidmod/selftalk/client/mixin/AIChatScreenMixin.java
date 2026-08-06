package com.maidmod.selftalk.client.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.client.SelfTalkPlayerSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 AI 聊天输入界面（AIChatScreen）左侧按钮组末尾新增一个小方块按钮，
 * 点击打开本模组的玩家自话设置界面（与选 AI 模型的入口同层级）。
 * <p>
 * 仅客户端加载（maid_self_talk.client.mixins.json，由主类按 dist 条件注册）。
 */
@Mixin(AIChatScreen.class)
public abstract class AIChatScreenMixin {

    @Shadow
    @Final
    private EntityMaid maid;

    @Inject(method = "init", at = @At("TAIL"))
    private void maid_self_talk$addSelfTalkSettingsButton(CallbackInfo ci) {
        AIChatScreen screen = (AIChatScreen) (Object) this;
        // 与 AIChatScreen.init 相同的布局参数：左组 3 个按钮之后追加第 4 个
        int size = 18;
        int gap = 2;
        int inputX = screen.width / 2 - 165;
        int inputY = screen.height / 2 + 58;
        int y = inputY - 28;
        int leftX = inputX - 8 + 3 * (size + gap);

        screen.addRenderableWidget(new FlatColorButton(leftX, y, size, size,
                Component.literal("💬"), b ->
                Minecraft.getInstance().setScreen(new SelfTalkPlayerSettingsScreen(this.maid)))
                .setTooltips("config.maid_self_talk.screen.self_talk_button.tip"));
    }
}
