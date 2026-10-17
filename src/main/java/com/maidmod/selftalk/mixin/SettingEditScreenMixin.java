package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.SettingEditScreen;
import com.maidmod.selftalk.client.MaidPersonaEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * TLM 原版「✎ 人设编辑」页（SettingEditScreen）的两处钩子：
 * <ul>
 *   <li>把右侧布娃娃预览从原「导出设定」下方的位置移到按钮列顶部空位；</li>
 *   <li>在原版保存（{@code saveConfig}，保存与保存并退出共用）末尾附带提交本 mod 的人设配置。</li>
 * </ul>
 * 页面本身由 AIChatScreenMixin 重定向构造为 {@link MaidPersonaEditScreen}（本模组的统一人设页），
 * 本类只服务该页面的原版行为调整；非本模组子类的原版页面不受影响。
 * <p>
 * 仅客户端加载：通过 mixins json 的 "client" 段声明，服务端不加载本类。
 */
@Mixin(SettingEditScreen.class)
public abstract class SettingEditScreenMixin {

    /**
     * 布娃娃预览改为顶部空位：原版 {@code drawMaid(graphics, customSetting.x + width + 73,
     * customSetting.y + 96, maid)} 的 posY 即第 3 个参数，改到 y = 34。
     * posX 不变（仍居中于右侧按钮列），只上移。
     */
    @ModifyArgs(method = "render",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/client/gui/entity/maid/ai/SettingEditScreen;drawMaid(Lnet/minecraft/client/gui/GuiGraphics;IILcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)V"))
    private void maid_self_talk$moveMaidPreview(Args args) {
        args.set(2, 34);
    }

    /**
     * 原版保存按钮附带保存：只在当前页面是本模组统一人设页时转交，
     * 页面内容无改动或正在等待上一请求时由页面自行跳过。
     */
    @Inject(method = "saveConfig", remap = false, at = @At("TAIL"))
    private void maid_self_talk$savePersonaAlongside(CallbackInfo ci) {
        if ((Object) this instanceof MaidPersonaEditScreen screen) {
            screen.onVanillaSave();
        }
    }
}
