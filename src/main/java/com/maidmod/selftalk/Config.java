package com.maidmod.selftalk;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置（COMMON 档，管理员统筹）。
 * <p>
 * 注意：本模组为全新实现，配置文件结构不兼容旧版 maid_self_talk。
 * 配置文件名仍为 maid_self_talk-common.toml（NeoForge 自动生成），
 * 旧文件中的未知键会被 NeoForge 忽略，新键使用默认值。
 * 1.0.1 起自话/欢迎提示词硬编码于 {@link SelfTalkPrompts}，不再从本配置读取。
 */
public final class Config {
    /** 总开关 */
    public static ModConfigSpec.BooleanValue ENABLED;
    /** 是否允许玩家独立设置（关闭后玩家设置项置灰且视为启用） */
    public static ModConfigSpec.BooleanValue PLAYER_OPTION_ENABLED;

    /** 态 1：主人在线 */
    public static ModConfigSpec.BooleanValue STATE1_ENABLED;
    /** 态 1 最小触发间隔（秒） */
    public static ModConfigSpec.IntValue STATE1_MIN_INTERVAL;
    /** 态 1 最大触发间隔（秒） */
    public static ModConfigSpec.IntValue STATE1_MAX_INTERVAL;
    /** 态 1：半径多少格内有玩家时才触发 */
    public static ModConfigSpec.DoubleValue STATE1_PLAYER_RANGE;
    /** 态 1 自言自语保留上下文条数 */
    public static ModConfigSpec.IntValue STATE1_KEEP_SELF_TALK_COUNT;

    /** 态 2：主人离线但附近有玩家 */
    public static ModConfigSpec.BooleanValue STATE2_ENABLED;
    /** 态 2 最小触发间隔（秒） */
    public static ModConfigSpec.IntValue STATE2_MIN_INTERVAL;
    /** 态 2 最大触发间隔（秒） */
    public static ModConfigSpec.IntValue STATE2_MAX_INTERVAL;
    /** 态 2：半径多少格内有玩家时才触发 */
    public static ModConfigSpec.DoubleValue STATE2_PLAYER_RANGE;
    /** 态 2 自言自语保留上下文条数 */
    public static ModConfigSpec.IntValue STATE2_KEEP_SELF_TALK_COUNT;

    /** 欢迎语开关 */
    public static ModConfigSpec.BooleanValue WELCOME_ENABLED;
    /** 玩家登录后的欢迎触发窗口（tick，20 tick = 1 秒） */
    public static ModConfigSpec.IntValue WELCOME_WINDOW_TICKS;

    /** 欢迎语秒级限流：每秒最多放行的欢迎触发次数（所有女仆共享） */
    public static ModConfigSpec.IntValue MAX_TRIGGER_PER_SECOND;
    /** 自话放行随机间隔区间下限（秒） */
    public static ModConfigSpec.IntValue SELF_TALK_MIN_INTERVAL;
    /** 自话放行随机间隔区间上限（秒） */
    public static ModConfigSpec.IntValue SELF_TALK_MAX_INTERVAL;

    /** 自话输出语言（TLM 官方模型设定多为英文，需要显式声明输出语言） */
    public static ModConfigSpec.ConfigValue<String> SELF_TALK_LANGUAGE;

    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("general");
        ENABLED = builder.comment("总开关，默认关闭。开启后女仆才会自言自语/欢迎/对话")
                .define("enabled", false);
        PLAYER_OPTION_ENABLED = builder.comment("是否允许玩家独立设置女仆是否触发自言自语。关闭后玩家设置项置灰，且视为启用")
                .define("playerOptionEnabled", true);
        builder.pop();

        builder.push("state_owner_online");
        STATE1_ENABLED = builder.comment("态 1：主人在线时女仆是否触发自言自语")
                .define("enabled", true);
        STATE1_MIN_INTERVAL = builder.comment("态 1 最小触发间隔（秒）")
                .defineInRange("minIntervalSeconds", 60, 10, 86400);
        STATE1_MAX_INTERVAL = builder.comment("态 1 最大触发间隔（秒）")
                .defineInRange("maxIntervalSeconds", 300, 10, 86400);
        STATE1_PLAYER_RANGE = builder.comment("态 1：半径多少格内有玩家时才触发")
                .defineInRange("playerRange", 16.0, 1.0, 512.0);
        STATE1_KEEP_SELF_TALK_COUNT = builder.comment("态 1：自言自语保留上下文条数。达到或超过该条数时触发一次遗忘，仅保留最近一次自言自语")
                .defineInRange("keepSelfTalkCount", 5, 1, 50);
        builder.pop();

        builder.push("state_owner_offline");
        STATE2_ENABLED = builder.comment("态 2：主人离线但附近有玩家时女仆是否触发自言自语")
                .define("enabled", true);
        STATE2_MIN_INTERVAL = builder.comment("态 2 最小触发间隔（秒）")
                .defineInRange("minIntervalSeconds", 120, 10, 86400);
        STATE2_MAX_INTERVAL = builder.comment("态 2 最大触发间隔（秒）")
                .defineInRange("maxIntervalSeconds", 600, 10, 86400);
        STATE2_PLAYER_RANGE = builder.comment("态 2：半径多少格内有玩家时才触发")
                .defineInRange("playerRange", 32.0, 1.0, 512.0);
        STATE2_KEEP_SELF_TALK_COUNT = builder.comment("态 2：自言自语保留上下文条数。达到或超过该条数时触发一次遗忘，仅保留最近一次自言自语")
                .defineInRange("keepSelfTalkCount", 3, 1, 50);
        builder.pop();

        builder.push("welcome");
        WELCOME_ENABLED = builder.comment("主人登录后女仆是否打招呼。欢迎语不受半径限制，加载区块内的女仆均可触发")
                .define("enabled", true);
        WELCOME_WINDOW_TICKS = builder.comment("玩家登录后的欢迎触发窗口（tick，20 tick = 1 秒）")
                .defineInRange("welcomeWindowTicks", 600, 20, 72000);
        builder.pop();

        builder.push("rate_limit");
        MAX_TRIGGER_PER_SECOND = builder.comment("""
                欢迎语限流：整个服务器每秒最多放行的欢迎触发次数（所有女仆共享）。
                防止主人登录时大量女仆同一瞬间并发建立 LLM 连接，导致端点 connect 超时。
                被限流的欢迎语不发请求、窗口期内每 tick 重试，玩家不会看到报错。""")
                .defineInRange("maxTriggerPerSecond", 1, 1, 20);
        SELF_TALK_MIN_INTERVAL = builder.comment("""
                自话限流：放行随机间隔区间下限（秒，全局共享）。
                两次自话派发之间至少间隔该时长，防启动/冷却同相时大量女仆并发建立 LLM 连接。
                被限流的自话不发请求、随机退避后重试，玩家不会看到报错。""")
                .defineInRange("selfTalkMinIntervalSeconds", 5, 1, 3600);
        SELF_TALK_MAX_INTERVAL = builder.comment("自话限流：放行随机间隔区间上限（秒），在区间内随机")
                .defineInRange("selfTalkMaxIntervalSeconds", 8, 1, 3600);
        builder.pop();

        builder.push("prompt");
        SELF_TALK_LANGUAGE = builder.comment("""
                自话输出语言（语言标签，如 zh_cn / en_us）。
                注意：TLM 官方模型的人设设定多为英文，若不显式声明语言，女仆自话可能输出英文。
                此配置会：1) 作为设定占位符的替换语言；2) 向自话提示词注入对应语言的输出指令。
                自话/欢迎提示词本身已硬编码在 SelfTalkPrompts 中（1.0.1 起不再从本配置读取），
                旧配置文件中的 selfTalkPrompt / selfTalkPromptOwnerNearby / welcomePrompt 键被忽略，不影响运行。""")
                .define("selfTalkLanguage", "zh_cn");
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }

    /**
     * 秒区间随机转 tick：min/max 倒置时自动交换（配置项无跨字段校验，
     * 管理员手改 toml 可能写出 min > max，交换后语义明确且区间恒正）。
     */
    public static int randomIntervalTicks(int minSeconds, int maxSeconds) {
        if (maxSeconds < minSeconds) {
            int temp = minSeconds;
            minSeconds = maxSeconds;
            maxSeconds = temp;
        }
        int minTicks = minSeconds * 20;
        int maxTicks = maxSeconds * 20;
        return minTicks + (int) (Math.random() * (maxTicks - minTicks + 1));
    }
}
