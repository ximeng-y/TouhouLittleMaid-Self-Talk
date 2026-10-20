package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.SettingEditScreen;
import com.maidmod.selftalk.client.MaidPersonaEditScreen;
import net.minecraft.client.gui.components.MultiLineEditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * TLM 原版「✎ 人设编辑」页（SettingEditScreen）的三处调整：
 * <ul>
 *   <li>把右侧布娃娃预览从原「导出设定」下方的位置移到按钮列顶部空位；</li>
 *   <li>缩小布娃娃预览（缩放以脚底为原点，只压缩模型整体与顶部，下缘由页面给出的 y 决定）；</li>
 *   <li>结构化模式下跳过原版 {@code mouseReleased} 对隐藏输入框的无条件派发。</li>
 * </ul>
 * 原版保存按钮已由 {@link MaidPersonaEditScreen} 在 init 阶段整体接管（显式保存入口），
 * 不再需要 {@code saveConfig()} 尾部的附带提交钩子；本模组子类之外的原版保存行为不受影响。
 * 页面本身由 AIChatScreenMixin 重定向构造为 {@link MaidPersonaEditScreen}（本模组的统一人设页），
 * 本类只服务该页面的原版行为调整；非本模组子类的原版页面不受影响。
 * <p>
 * remap 说明：{@code render} 与 {@code mouseReleased} 是原版方法覆写、{@code @At} 里指向的
 * {@link MultiLineEditBox#mouseReleased} 是原版类方法，生产环境需经 refmap 重映射（remap 取默认
 * true）；{@code drawMaid} 是 TLM 自有成员，取 {@code remap = false}，
 * 其内部的原版实体渲染调用单独保留 {@code remap = true}。
 * <p>
 * 仅客户端加载：通过 mixins json 的 "client" 段声明，服务端不加载本类。
 */
@Mixin(SettingEditScreen.class)
public abstract class SettingEditScreenMixin {

    /**
     * 布娃娃预览移到按钮列顶部空位：原版 {@code drawMaid(graphics, customSetting.x + width + 73,
     * customSetting.y + 96, maid)} 的 x / y 均从输入框位置推导，而结构化模式下输入框已被挪出
     * 屏幕，因此两个坐标都改由页面按定位到的原版布局与模型 render_item_scale 给出；
     * 未定位到输入框时不改参数。分别修改坐标，避免 {@code @ModifyArgs} 在 Forge
     * 生产环境引入动态 Args 子类。
     */
    @ModifyArg(method = "render", index = 1,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/client/gui/entity/maid/ai/SettingEditScreen;drawMaid(Lnet/minecraft/client/gui/GuiGraphics;IILcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)V"))
    private int maid_self_talk$moveMaidPreviewX(int originalX) {
        if ((Object) this instanceof MaidPersonaEditScreen screen) {
            int[] pos = screen.maidPreviewPos();
            if (pos != null) {
                return pos[0];
            }
        }
        return originalX;
    }

    @ModifyArg(method = "render", index = 2,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/client/gui/entity/maid/ai/SettingEditScreen;drawMaid(Lnet/minecraft/client/gui/GuiGraphics;IILcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)V"))
    private int maid_self_talk$moveMaidPreviewY(int originalY) {
        if ((Object) this instanceof MaidPersonaEditScreen screen) {
            int[] pos = screen.maidPreviewPos();
            if (pos != null) {
                return pos[1];
            }
        }
        return originalY;
    }

    /**
     * 只缩小本模组人设页的模型，保留模型资源自带的尺寸比例。
     * renderEntityInInventory 的缩放以模型脚底为原点，因此这里只压缩整体与顶部，
     * 下缘位置由 {@link MaidPersonaEditScreen#maidPreviewPos()} 给出的 y 固定。
     */
    @ModifyArg(method = "drawMaid", remap = false, index = 3,
            at = @At(value = "INVOKE", remap = true,
                    target = "Lnet/minecraft/client/gui/screens/inventory/InventoryScreen;renderEntityInInventoryFollowsMouse(Lnet/minecraft/client/gui/GuiGraphics;IIIFFLnet/minecraft/world/entity/LivingEntity;)V"))
    private int maid_self_talk$shrinkMaidPreview(int originalScale) {
        if ((Object) this instanceof MaidPersonaEditScreen) {
            return Math.max(1, Math.round(originalScale * 0.8F));
        }
        return originalScale;
    }

    /**
     * 原版 {@code mouseReleased} 末尾无条件把释放事件派发给 {@code customSetting} 字段
     * （不受 children 影响）：结构化模式下输入框已隐藏，跳过这次派发，释放事件只走原版
     * children（焦点）逻辑；整段模式下原样透传。
     */
    @Redirect(method = "mouseReleased",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/MultiLineEditBox;mouseReleased(DDI)Z"))
    private boolean maid_self_talk$skipHiddenBoxRelease(MultiLineEditBox box, double x, double y, int button) {
        if ((Object) this instanceof MaidPersonaEditScreen screen && screen.isStructuredMode()) {
            return false;
        }
        return box.mouseReleased(x, y, button);
    }
}
