package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/**
 * 可选设置导航桥（仅客户端）：供 AgentTweaks 等外部模组以反射方式接入。
 * 公开方法与 NeoForge 线保持一致，不引用外部集成模组的类型。
 */
@OnlyIn(Dist.CLIENT)
public final class SelfTalkSettingsBridge {

    /** 可空的打开委托；未注册时保留本模组独立入口 */
    private static BiConsumer<EntityMaid, Screen> settingsOpener;

    private SelfTalkSettingsBridge() {
    }

    /** 注册或清除打开委托；传入 null 恢复独立打开行为 */
    public static void setSettingsOpener(BiConsumer<EntityMaid, Screen> opener) {
        settingsOpener = opener;
    }

    /** 创建真实设置页；父界面为 null 时关闭后返回游戏 */
    public static Screen createSettingsScreen(EntityMaid maid, Screen parent) {
        return new SelfTalkPlayerSettingsScreen(maid, parent);
    }

    /**
     * 设置页浮层打开时拒绝导航，不保存、不切页；否则保存 Prompt 并清焦点。
     * 非本模组设置页直接允许导航。
     */
    public static boolean prepareForNavigation(Screen screen) {
        if (screen instanceof SelfTalkPlayerSettingsScreen selfTalkScreen) {
            return selfTalkScreen.prepareForNavigation();
        }
        return true;
    }

    /** 已注册委托则交给外部，否则由本模组独立打开 */
    public static void openSettings(EntityMaid maid, Screen parent) {
        BiConsumer<EntityMaid, Screen> opener = settingsOpener;
        if (opener != null) {
            opener.accept(maid, parent);
        } else {
            Minecraft.getInstance().setScreen(createSettingsScreen(maid, parent));
        }
    }
}
