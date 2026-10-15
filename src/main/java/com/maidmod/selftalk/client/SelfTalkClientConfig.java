package com.maidmod.selftalk.client;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 本地（客户端）显示设置。
 * <p>
 * 配置档 CLIENT、文件名 {@code maid_self_talk-client.toml}，按本地游戏实例持久化——
 * 不按服务器、世界、女仆或登录账号分别保存。服务端不读取本配置。
 */
public final class SelfTalkClientConfig {

    /** 是否显示自话/互聊的等待、工具提示与思考气泡（默认开启；关闭后最终回答气泡、欢迎语与普通 AI 聊天气泡不受影响） */
    public static ForgeConfigSpec.BooleanValue SHOW_AUTONOMOUS_WAITING_BUBBLES;

    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SHOW_AUTONOMOUS_WAITING_BUBBLES = builder.comment("""
                是否显示自话/互聊的等待、工具提示与思考气泡（仅影响本地玩家看到的所有女仆）。
                默认开启；关闭后仅隐藏上述等待类气泡，最终回答文字气泡、欢迎语、普通 AI 聊天、
                聊天栏与语音不受影响。""")
                .define("showAutonomousWaitingBubbles", true);
        SPEC = builder.build();
    }

    private SelfTalkClientConfig() {
    }

    public static boolean showAutonomousWaitingBubbles() {
        return SHOW_AUTONOMOUS_WAITING_BUBBLES.get();
    }

    /** 设置并立即保存（设置界面按钮点击时调用，与 Cloth Config 的 set+save 持久化方式一致） */
    public static void setShowAutonomousWaitingBubbles(boolean value) {
        SHOW_AUTONOMOUS_WAITING_BUBBLES.set(value);
        SHOW_AUTONOMOUS_WAITING_BUBBLES.save();
    }
}