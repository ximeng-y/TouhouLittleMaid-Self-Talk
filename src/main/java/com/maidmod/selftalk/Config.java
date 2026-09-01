package com.maidmod.selftalk;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 模组配置（COMMON 档，管理员统筹）。
 * <p>
 * 注意：本模组为全新实现，配置文件结构不兼容旧版 maid_self_talk。
 * 配置文件名仍为 maid_self_talk-common.toml（Forge 自动生成），
 * 旧文件中的未知键会被 Forge 忽略，新键使用默认值。
 * 1.0.1 起自话/欢迎提示词硬编码于 {@link SelfTalkPrompts}，不再从本配置读取。
 * <p>
 * 注意：本分支为 Forge 1.20.1，<b>无 NeoForge 的 ConfigFileWatcher 热重载</b>——
 * 替换 toml 后需重启服务端才生效（1.21.1 线替换即生效，双分支行为不同）。
 */
public final class Config {
    /** 总开关 */
    public static ForgeConfigSpec.BooleanValue ENABLED;
    /** 是否允许玩家自定义配置（关闭后玩家设置项置灰，自话/互聊视为启用，自定义 Prompt 不注入、Tool 不开启） */
    public static ForgeConfigSpec.BooleanValue PLAYER_OPTION_ENABLED;
    /** 是否允许女仆的自话/互聊真正调用工具（Tool 功能总闸，玩家层面开关在此基础上二次约束） */
    public static ForgeConfigSpec.BooleanValue TOOL_CALL_ENABLED;

    /** 态 1：主人在线 */
    public static ForgeConfigSpec.BooleanValue STATE1_ENABLED;
    /** 态 1 最小触发间隔（秒） */
    public static ForgeConfigSpec.IntValue STATE1_MIN_INTERVAL;
    /** 态 1 最大触发间隔（秒） */
    public static ForgeConfigSpec.IntValue STATE1_MAX_INTERVAL;
    /** 态 1：半径多少格内有玩家时才触发 */
    public static ForgeConfigSpec.DoubleValue STATE1_PLAYER_RANGE;
    /** 态 1 自言自语保留上下文条数 */
    public static ForgeConfigSpec.IntValue STATE1_KEEP_SELF_TALK_COUNT;

    /** 态 2：主人离线但附近有玩家 */
    public static ForgeConfigSpec.BooleanValue STATE2_ENABLED;
    /** 态 2 最小触发间隔（秒） */
    public static ForgeConfigSpec.IntValue STATE2_MIN_INTERVAL;
    /** 态 2 最大触发间隔（秒） */
    public static ForgeConfigSpec.IntValue STATE2_MAX_INTERVAL;
    /** 态 2：半径多少格内有玩家时才触发 */
    public static ForgeConfigSpec.DoubleValue STATE2_PLAYER_RANGE;
    /** 态 2 自言自语保留上下文条数 */
    public static ForgeConfigSpec.IntValue STATE2_KEEP_SELF_TALK_COUNT;

    /** 欢迎语开关 */
    public static ForgeConfigSpec.BooleanValue WELCOME_ENABLED;
    /** 玩家登录后的欢迎触发窗口（tick，20 tick = 1 秒） */
    public static ForgeConfigSpec.IntValue WELCOME_WINDOW_TICKS;

    /** 欢迎语秒级限流：每秒最多放行的欢迎触发次数（所有女仆共享） */
    public static ForgeConfigSpec.IntValue MAX_TRIGGER_PER_SECOND;
    /** 自话放行随机间隔区间下限（秒） */
    public static ForgeConfigSpec.IntValue SELF_TALK_MIN_INTERVAL;
    /** 自话放行随机间隔区间上限（秒） */
    public static ForgeConfigSpec.IntValue SELF_TALK_MAX_INTERVAL;
    /** 顺延队列上限：每只女仆忙时积压的自话/互聊请求数上限，超出即吞（默认顺延，队列过多才吞） */
    public static ForgeConfigSpec.IntValue DEFER_QUEUE_MAX;

    /** 自话输出语言（TLM 官方模型设定多为英文，需要显式声明输出语言） */
    public static ForgeConfigSpec.ConfigValue<String> SELF_TALK_LANGUAGE;

    // ===== 互聊 =====
    /** 互聊总开关 */
    public static ForgeConfigSpec.BooleanValue INTER_CHAT_ENABLED;
    /** 互聊最小触发间隔（秒） */
    public static ForgeConfigSpec.IntValue INTER_CHAT_MIN_INTERVAL;
    /** 互聊最大触发间隔（秒） */
    public static ForgeConfigSpec.IntValue INTER_CHAT_MAX_INTERVAL;
    /** 互聊：半径多少格内有玩家时才触发 */
    public static ForgeConfigSpec.DoubleValue INTER_CHAT_PLAYER_RANGE;
    /** 互聊：自身多少格内有另一只女仆时才触发 */
    public static ForgeConfigSpec.DoubleValue INTER_CHAT_MAID_RANGE;
    /** 互聊保留轮数（问/答算一轮，单问也算一轮） */
    public static ForgeConfigSpec.IntValue INTER_CHAT_KEEP_ROUNDS;
    /** 互聊连续触发概率（0~1） */
    public static ForgeConfigSpec.DoubleValue INTER_CHAT_CHAIN_PROBABILITY;
    public static ForgeConfigSpec.IntValue INTER_CHAT_MAX_CHAIN_ROUNDS;
    /** 互聊对锁时长（秒）：A 对 C 发起互聊后，二者在连续互聊结束前互相对锁，超时兜底自动解除 */
    public static ForgeConfigSpec.IntValue INTER_CHAT_PAIR_LOCK_SECONDS;

    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("general");
        ENABLED = builder.comment("总开关，默认关闭。开启后女仆才会自言自语/欢迎/对话")
                .define("enabled", false);
        PLAYER_OPTION_ENABLED = builder.comment("""
                允许玩家自定义配置。玩家能否单独配置自己的女仆（自言自语、互聊、自定义 Prompt、Tool 调用）。
                关闭后玩家设置项置灰，自话/互聊视为启用，自定义 Prompt 不注入、Tool 调用不开启。""")
                .define("playerOptionEnabled", true);
        TOOL_CALL_ENABLED = builder.comment("""
                是否允许女仆的自言自语/互聊真正调用工具（切换工作任务、坐下、跟随等）。
                开启后女仆可以在自话/互聊中改变游戏状态，并显著增加 token 消耗，默认关闭。
                玩家层面的开关还需「允许玩家自定义配置」开启时才生效。""")
                .define("toolCallEnabled", false);
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
        STATE1_KEEP_SELF_TALK_COUNT = builder.comment("态 1：自言自语保留上下文条数。超过该条数时触发一次遗忘，仅保留最近一次自言自语")
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
        STATE2_KEEP_SELF_TALK_COUNT = builder.comment("态 2：自言自语保留上下文条数。超过该条数时触发一次遗忘，仅保留最近一次自言自语")
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
        DEFER_QUEUE_MAX = builder.comment("""
                顺延限流：每只女仆忙时积压的自话/互聊请求数上限（默认顺延）。
                顺延请求会在女仆空闲后按序派发（派发时才构建消息，保证上下文稳定）；
                积压超出该上限时才吞掉新请求（队列过多才吞，避免挤压自然触发队列）。""")
                .defineInRange("deferQueueMax", 2, 1, 10);
        builder.pop();

        builder.push("prompt");
        SELF_TALK_LANGUAGE = builder.comment("""
                自话输出语言（语言标签，如 zh_cn / en_us / ja_jp，支持任意合法语言标签，非法值回退 zh_cn）。
                注意：TLM 官方模型的人设设定多为英文，若不显式声明语言，女仆自话可能输出英文。
                此配置会：1) 作为设定占位符的替换语言；2) 向自话提示词注入对应语言的输出指令。
                自话/欢迎提示词本身已硬编码在 SelfTalkPrompts 中（1.0.1 起不再从本配置读取），
                旧配置文件中的 selfTalkPrompt / selfTalkPromptOwnerNearby / welcomePrompt 键被忽略，不影响运行。""")
                .define("selfTalkLanguage", "zh_cn");
        builder.pop();

        builder.push("inter_maid_chat");
        INTER_CHAT_ENABLED = builder.comment("女仆互聊总开关，默认关闭。开启后满足玩家距离与女仆间距离的女仆才会发起互聊")
                .define("enabled", false);
        INTER_CHAT_MIN_INTERVAL = builder.comment("互聊最小触发间隔（秒）")
                .defineInRange("minIntervalSeconds", 300, 10, 86400);
        INTER_CHAT_MAX_INTERVAL = builder.comment("互聊最大触发间隔（秒）")
                .defineInRange("maxIntervalSeconds", 600, 10, 86400);
        INTER_CHAT_PLAYER_RANGE = builder.comment("互聊：半径多少格内有玩家时才触发（玩家距离）")
                .defineInRange("playerRange", 16.0, 1.0, 512.0);
        INTER_CHAT_MAID_RANGE = builder.comment("互聊：自身多少格内有另一只女仆时才触发（女仆间距离）")
                .defineInRange("maidRange", 8.0, 1.0, 512.0);
        INTER_CHAT_KEEP_ROUNDS = builder.comment("互聊保留轮数。问/答算一轮，单问无答也算一轮；达到该轮数时触发遗忘，仅保留最近一条消息")
                .defineInRange("keepRounds", 5, 1, 50);
        INTER_CHAT_CHAIN_PROBABILITY = builder.comment("互聊连续触发概率（0~1），每轮回答后按此概率决定是否让对方继续回应")
                .defineInRange("chainProbability", 0.3, 0.0, 1.0);
        INTER_CHAT_MAX_CHAIN_ROUNDS = builder.comment("一次互聊会话的最大消息条数（含发起者消息），达到后强制结束本次互聊。为连续概率配到 1.0 等极端配置兜底，防止无限链式往返消耗 token")
                .defineInRange("maxChainRounds", 10, 1, 50);
        INTER_CHAT_PAIR_LOCK_SECONDS = builder.comment("""
                互聊对锁：A 对 C 发起互聊后，二者在「连续互聊结束」前互相对锁——
                不能发起、也不能被发起互聊（其它女仆的随机候选池不再包含这对）。
                链自然结束/请求失败/女仆死亡卸载时均会提前解锁；该时长仅为
                回调永不返回等无法感知的异常中断时的超时兜底。""")
                .defineInRange("pairLockSeconds", 120, 10, 600);
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
