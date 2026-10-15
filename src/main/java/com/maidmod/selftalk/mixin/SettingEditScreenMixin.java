package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.SettingEditScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.client.MaidPersonaEditScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在原版「✎ 人设编辑」页（SettingEditScreen）右侧按钮列顶部空位新增「结构化草稿」入口。
 * <p>
 * 位置：与导出按钮同列（原版 {@code posX + 265}）、位于导出按钮上方，不挤占原版保存 / 保存并退出 /
 * 返回三枚按钮与女仆预览区域，因此不改变原版页面的既有布局与行为。
 * <p>
 * 点击进入结构化草稿编辑页，并把当前这个原版页面实例作为返回目标传入——返回时回到同一个页面，
 * 不新建原版页面，也不触发原版保存。
 * <p>
 * 本类继承 {@link Screen}（目标类的父类）：mixin 合并时父类不参与合并，仅用于
 * 编译期让 {@code this.addRenderableWidget(...)} 满足 Java protected 访问规则；
 * 字节码中的方法引用会随 reobf 正确重映射为生产名 {@code m_142416_}。mixin 类不会被实例化，
 * 构造器仅满足语法。
 * <p>
 * 注入点 {@code init} 是原版 Screen 方法、被 TLM 覆写：生产环境里该方法名是官方混淆名
 * （{@code m_7856_}），故此处<b>必须</b>走默认 remap（true）让 refmap 完成映射，
 * 与 TLM 自有方法（名前保持 mojmap、须 remap=false）相反。
 * <p>
 * 仅客户端加载：通过 mixins json 的 "client" 段声明，服务端不加载本类。
 */
@Mixin(SettingEditScreen.class)
public abstract class SettingEditScreenMixin extends Screen {

    @Shadow(remap = false)
    @Final
    private EntityMaid maid;

    protected SettingEditScreenMixin() {
        super(Component.translatable("config.maid_self_talk.screen.persona_draft.title"));
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void maid_self_talk$addPersonaDraftButton(CallbackInfo ci) {
        SettingEditScreen screen = (SettingEditScreen) (Object) this;
        // 与导出按钮同列（原版 init 中导出按钮为 posX + 265，y = ownerName.getY() = 30），
        // 上移一格取顶部空位；posX 由原版按屏幕中线算出，此处同式复现
        int posX = this.width / 2 - 195;
        FlatColorButton button = new FlatColorButton(posX + 265, 5, 128, 20,
                Component.translatable("config.maid_self_talk.screen.persona_draft.entry"),
                b -> Minecraft.getInstance().setScreen(new MaidPersonaEditScreen(screen, this.maid)));
        this.addRenderableWidget(button);
    }
}
