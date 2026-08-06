package com.maidmod.selftalk;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置（COMMON 档，管理员统筹）。
 * <p>
 * 注意：本模组为全新实现，配置文件结构不兼容旧版 maid_self_talk。
 * 配置文件名仍为 maid_self_talk-common.toml（NeoForge 自动生成），
 * 旧文件中的未知键会被 NeoForge 忽略，新键使用默认值。
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

    /** 自言自语提示词 */
    public static ModConfigSpec.ConfigValue<String> SELF_TALK_PROMPT;
    /** 主人在身边时的自言自语提示词 */
    public static ModConfigSpec.ConfigValue<String> SELF_TALK_PROMPT_OWNER_NEARBY;
    /** 欢迎语提示词 */
    public static ModConfigSpec.ConfigValue<String> WELCOME_PROMPT;
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

        builder.push("prompt");
        SELF_TALK_PROMPT = builder.comment("""
                自言自语提示词（主人在身边时使用另一条：selfTalkPromptOwnerNearby）。
                系统会随机纳入几类游戏情境信息（位置/附近实体/装备等）拼入该提示词；
                主人在身边时会额外强制纳入女仆状态（含当前工作状态）。
                提示词会作为 user 消息发送给 AI（与玩家 chat 相同格式，保证上下文前缀缓存一致），
                但不会写入聊天记录，也不会显示在女仆的聊天记录界面中。
                请保证提示词引导女仆说出贴合人设、不同质化、不重复的话，且输出中不暴露任何系统信息。""")
                .define("selfTalkPrompt", """
                        下面这段话是发给你的内心独白指令，不是玩家说的话，也不是其他人对你说的话。
                        你正独自待在当前环境中。请以你自己的身份，自然地说一句心里话——就像四下无人时，你脱口而出的自言自语。

                        要求：
                        1. 只说一句话或一小段话，口语化、自然，贴合你的性格和当下的处境。
                        2. 可以是对眼前景象的感慨、心里惦记的事、想到某人时的小声嘀咕、打发时间的碎碎念。
                        3. 结合下方提供的情境信息，让内容与当下环境贴合。
                        4. 如果上方聊天记录里已有你说过的话，请说点新的，不要重复、不要复读。
                        5. 直接输出你要说的话本身，不要任何解释、标注、括号说明，也不要称呼任何人。""");
        SELF_TALK_PROMPT_OWNER_NEARBY = builder.comment("主人在身边（16 格内）时的自言自语提示词，规则同 selfTalkPrompt")
                .define("selfTalkPromptOwnerNearby", """
                        下面这段话是发给你的内心独白指令，不是玩家说的话，也不是其他人对你说的话。
                        你的主人就在你身边，你们正待在当前环境中。请以你自己的身份，在心里默默嘀咕一句——就像主人在旁边时，你心里想着、偶尔小声嘟囔的那种话。

                        要求：
                        1. 只说一句话或一小段话，口语化、自然，贴合你的性格和当下的处境。
                        2. 可以是对眼前景象的感慨、心里惦记的事、想到某人时的小声嘀咕、打发时间的碎碎念。
                        3. 结合下方提供的情境信息，让内容与当下环境贴合。
                        4. 如果上方聊天记录里已有你说过的话，请说点新的，不要重复、不要复读。
                        5. 直接输出你要说的话本身，不要任何解释、标注、括号说明，也不要称呼任何人。""");
        WELCOME_PROMPT = builder.comment("欢迎语提示词，规则同自言自语提示词")
                .define("welcomePrompt", """
                        下面这段话是发给你的打招呼指令。
                        你的主人刚刚上线，正来到你身边。请以你自己的身份，自然地向主人打个招呼——就像见到久别重逢的人时你会说的话。

                        要求：
                        1. 只问候一句话或一小段话，口语化、自然，贴合你的性格和你们的关系。
                        2. 可以提到主人的名字，也可以不提，按你们的关系来。
                        3. 不要重复你之前说过的话。
                        4. 直接输出你要说的话本身，不要任何解释、标注、括号说明。""");
        SELF_TALK_LANGUAGE = builder.comment("""
                自话输出语言（语言标签，如 zh_cn / en_us）。
                注意：TLM 官方模型的人设设定多为英文，若不显式声明语言，女仆自话可能输出英文。
                此配置会：1) 作为设定占位符的替换语言；2) 向自话提示词注入对应语言的输出指令。""")
                .define("selfTalkLanguage", "zh_cn");
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}
