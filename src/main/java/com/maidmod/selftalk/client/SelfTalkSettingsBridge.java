package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/**
 * 可选设置导航桥（仅客户端）：供 AgentTweaks 等外部模组以反射方式接入女仆设置界面。
 * <p>
 * 本类只暴露固定公开静态方法，不引用任何外部模组类型；未注册 opener 时保持 SelfTalk
 * 独立打开行为（💬 按钮直接打开本模组设置界面），不要求外部模组存在。
 */
@OnlyIn(Dist.CLIENT)
public final class SelfTalkSettingsBridge {

    /** 外部接入方注册的打开委托（可空 = 未接入，走本模组默认打开） */
    private static BiConsumer<EntityMaid, Screen> settingsOpener;

    private SelfTalkSettingsBridge() {
    }

    /** 注册或清除打开委托；传入 null 恢复本模组独立打开行为 */
    public static void setSettingsOpener(BiConsumer<EntityMaid, Screen> opener) {
        settingsOpener = opener;
    }

    /** 创建真实的女仆设置界面（带返回父界面；父界面为 null 时保持原关闭行为） */
    public static Screen createSettingsScreen(EntityMaid maid, Screen parent) {
        return new SelfTalkPlayerSettingsScreen(maid, parent);
    }

    /**
     * 离开设置页前的导航协商：若当前是本模组设置界面且环境浮层打开，返回 false
     * （不保存、不切页，防止浮层输入状态带出）；否则保存 Prompt 编辑并清除焦点后返回 true；
     * 非本模组界面直接返回 true。
     */
    public static boolean prepareForNavigation(Screen screen) {
        if (screen instanceof SelfTalkPlayerSettingsScreen selfTalkScreen) {
            return selfTalkScreen.prepareForNavigation();
        }
        return true;
    }

    /** 打开设置界面：已注册委托则交给外部，否则按本模组默认方式打开 */
    public static void openSettings(EntityMaid maid, Screen parent) {
        BiConsumer<EntityMaid, Screen> opener = settingsOpener;
        if (opener != null) {
            opener.accept(maid, parent);
        } else {
            Minecraft.getInstance().setScreen(createSettingsScreen(maid, parent));
        }
    }
}
