package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.SettingEditScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.client.MaidPersonaEditScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * 在原版「✎ 人设编辑」页（SettingEditScreen）右侧按钮列顶部空位新增「结构化草稿」入口。
 * <p>
 * 位置：与导出按钮同列（原版 {@code posX + 265}）、位于导出按钮上方，不挤占原版保存 / 保存并退出 /
 * 返回三枚按钮与女仆预览区域，因此不改变原版页面的既有布局与行为。
 * <p>
 * 点击进入结构化草稿编辑页，并把当前这个原版页面实例作为返回目标传入——返回时回到同一个页面，
 * 不新建原版页面，也不触发原版保存。
 * <p>
 * 仅客户端加载：通过 mixins json 的 "client" 段声明，服务端不加载本类。
 */
@Mixin(SettingEditScreen.class)
public abstract class SettingEditScreenMixin {

    @Shadow
    @Final
    private EntityMaid maid;

    @Inject(method = "init", at = @At("TAIL"))
    private void maid_self_talk$addPersonaDraftButton(CallbackInfo ci) {
        SettingEditScreen screen = (SettingEditScreen) (Object) this;
        // 与导出按钮同列（原版 init 中导出按钮为 posX + 265，y = ownerName.getY() = 30），
        // 上移一格取顶部空位；posX 由原版按屏幕中线算出，此处同式复现
        int posX = screen.width / 2 - 195;
        FlatColorButton button = new FlatColorButton(posX + 265, 5, 128, 20,
                Component.translatable("config.maid_self_talk.screen.persona_draft.entry"),
                b -> Minecraft.getInstance().setScreen(new MaidPersonaEditScreen(screen, this.maid)));
        addRenderableWidgetReflect(screen, button);
    }

    /**
     * Screen.addRenderableWidget 为 protected 且定义在父类，
     * mixin 的 @Shadow 对继承方法解析不稳定，此处直接反射调用。
     */
    private static void addRenderableWidgetReflect(Screen screen, AbstractWidget widget) {
        try {
            Method method = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class);
            method.setAccessible(true);
            method.invoke(screen, widget);
        } catch (ReflectiveOperationException e) {
            MaidSelfTalkMod.LOGGER.warn("Failed to add persona draft button to SettingEditScreen", e);
        }
    }
}
