package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.client.SelfTalkPlayerSettingsScreen;
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
 * 在 AI 聊天输入界面（AIChatScreen）左侧按钮组末尾新增一个小方块按钮，
 * 点击打开本模组的玩家自话设置界面（与选 AI 模型的入口同层级）。
 * <p>
 * 仅客户端加载：通过 mixins json 的 "client" 段声明，服务端不加载本类。
 * <p>
 * 本类继承 {@link Screen}（目标类的父类）：mixin 合并时父类不参与合并，仅用于
 * 编译期让 {@code this.addRenderableWidget(...)} 满足 Java protected 访问规则；
 * 字节码中的方法引用会随 reobf 正确重映射为生产名 {@code m_142416_}，
 * 字符串反射则会静默失败（1.21.1 旧实现的教训）。mixin 类不会被实例化，构造器仅满足语法。
 */
@Mixin(AIChatScreen.class)
public abstract class AIChatScreenMixin extends Screen {

    @Shadow(remap = false)
    @Final
    private EntityMaid maid;

    protected AIChatScreenMixin() {
        super(Component.translatable("maid_self_talk.screen.player_settings.title"));
    }

    /**
     * 注入点选 addLeftButtons（TLM 自有 private 方法，非覆写原版）：
     * Forge 1.20.1 生产环境对覆写原版的方法会重命名为官方混淆名（init → m_7856_），
     * 而 TLM 自有方法名保持 mojmap，dev/prod 均可注入。
     * 按钮坐标直接用目标方法参数计算，不依赖硬编码布局。
     */
    @Inject(method = "addLeftButtons", remap = false, at = @At("TAIL"))
    private void maid_self_talk$addSelfTalkSettingsButton(int leftX, int y, int size, int gap, CallbackInfo ci) {
        int x = leftX + 3 * (size + gap);  // 左组 3 个按钮之后
        FlatColorButton button = new FlatColorButton(x, y, size, size,
                Component.literal("💬"), b ->
                Minecraft.getInstance().setScreen(new SelfTalkPlayerSettingsScreen(this.maid)))
                .setTooltips("config.maid_self_talk.screen.self_talk_button.tip");
        this.addRenderableWidget(button);
    }
}
