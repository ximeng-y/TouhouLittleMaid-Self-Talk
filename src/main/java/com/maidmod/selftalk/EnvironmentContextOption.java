package com.maidmod.selftalk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境上下文目录项（固定 37 项）。
 * <p>
 * 本类不依赖 Minecraft / TLM 类型，便于与 {@link EnvironmentContextSelection} 一起脱离游戏自检。
 * <p>
 * 目录顺序同时是界面排列顺序，也是抽样结果的输出顺序（抽样本身是无放回随机，
 * 但渲染时一律按目录顺序输出，避免每次请求的上下文顺序抖动影响前缀缓存）。
 * <p>
 * 每项独占一行，不拆成「每件装备」「每条伤害记录」等动态条目。
 */
public record EnvironmentContextOption(String key, Source source,
                                       EnvironmentContextMode defaultMode, Gate gate) {

    /** 数据来源类型 */
    public enum Source {
        /** TLM 固定信息（prompt 类分类，原本由 UserPromptContexts 前缀恒量注入） */
        TLM_PROMPT,
        /** TLM 原随机信息（tool 类分类，原本按分类随机抽 1~3 类） */
        TLM_RANDOM,
        /** 环境事件（死亡／受伤／神龛复活／自身击杀） */
        EVENT,
        /** 实时自身状态（着火／水下缺氧） */
        REALTIME,
        /** 饰品栏（女仆原生饰品栏、女仆与主人的 Curios 栏）：本 mod 直接读取，不走 TLM 提供者与 GameContextRegister */
        ACCESSORY
    }

    /**
     * 功能门类型：决定该项在界面上的可用性，以及注入侧是否放行。
     * <p>
     * 三层语义必须分开：玩家偏好（{@link EnvironmentContextMode}）／管理员与提供者是否可用（本枚举）／
     * 本轮是否有实际数据（运行时判定，<b>不</b>影响界面可配置性）。
     */
    public enum Gate {
        /** 依赖对应 TLM 提供者在服务端注册表中实际存在 */
        PROVIDER,
        /** 只受环境感知总开关约束 */
        EVENT_MASTER,
        /** 受环境感知总开关 + 玩家受伤感知开关约束 */
        EVENT_HURT,
        /** 受环境感知总开关 + 女仆自身感知开关约束 */
        EVENT_SELF,
        /** 依赖附近女仆身份提供者是否实际注册（启动时注册，改动需重启） */
        IDENTITY_PROVIDER,
        /** 本 mod 内置读取，不依赖任何 TLM 提供者，也不依赖 Curios */
        BUILTIN,
        /** 依赖 Curios 实际安装（TLM 的 Curios 兼容已初始化） */
        CURIOS,
        /** 依赖 Curios 已安装且 TLM 的女仆 Curios 支持未被管理员关闭 */
        MAID_CURIOS
    }

    /** 是否与目录默认模式不同（即是否需要落盘为玩家覆盖） */
    public boolean isOverride(EnvironmentContextMode mode) {
        return mode != defaultMode;
    }

    // ===== 目录（顺序即界面顺序） =====

    private static List<EnvironmentContextOption> buildAll() {
        return List.of(
                // 1~5：TLM 原随机信息，默认随机
                random("nearby_entities"),
                random("mainhand_item"),
                random("offhand_item"),
                random("inventory_items"),
                random("armor_items"),
                // 6~7：饰品栏（本 mod 直接读取），默认随机
                accessory(KEY_MAID_BAUBLES, Gate.BUILTIN),
                accessory(KEY_MAID_CURIOS, Gate.MAID_CURIOS),
                // 8~17：TLM 原随机信息，默认随机
                random("self_position"),
                random("user_position"),
                random("distance_to_user"),
                random("light_level"),
                random("user_name"),
                random("user_healthy"),
                random("user_mainhand"),
                random("user_armor"),
                // 16：主人 Curios 饰品（本 mod 直接读取），默认随机
                accessory(KEY_USER_CURIOS, Gate.CURIOS),
                random("effects"),
                // 18~25：TLM 固定信息 status，默认必定进入
                prompt("healthy"),
                prompt("sleep_state"),
                prompt("follow_state"),
                prompt("sitting"),
                prompt("riding"),
                prompt("schedule"),
                prompt("activity"),
                prompt("work_task"),
                // 26~29：TLM 固定信息 world，默认必定进入
                prompt("game_time"),
                prompt("weather"),
                prompt("dimension"),
                prompt("biome"),
                // 30：附近女仆身份，原附着于随机分类，默认随机
                new EnvironmentContextOption(KEY_IDENTITY,
                        Source.TLM_RANDOM, EnvironmentContextMode.RANDOM, Gate.IDENTITY_PROVIDER),
                // 31~35：环境事件与实时自身状态，默认必定进入
                new EnvironmentContextOption(KEY_DEATHS,
                        Source.EVENT, EnvironmentContextMode.ALWAYS, Gate.EVENT_MASTER),
                new EnvironmentContextOption(KEY_PLAYER_HURT,
                        Source.EVENT, EnvironmentContextMode.ALWAYS, Gate.EVENT_HURT),
                new EnvironmentContextOption(KEY_SELF_HURT,
                        Source.EVENT, EnvironmentContextMode.ALWAYS, Gate.EVENT_SELF),
                new EnvironmentContextOption(KEY_ON_FIRE,
                        Source.REALTIME, EnvironmentContextMode.ALWAYS, Gate.EVENT_SELF),
                new EnvironmentContextOption(KEY_DROWNING,
                        Source.REALTIME, EnvironmentContextMode.ALWAYS, Gate.EVENT_SELF),
                // 36~37：女仆本人事件（神龛复活、自身击杀），只记录到女仆本人，默认必定进入
                new EnvironmentContextOption(KEY_SELF_REVIVE,
                        Source.EVENT, EnvironmentContextMode.ALWAYS, Gate.EVENT_MASTER),
                new EnvironmentContextOption(KEY_SELF_KILLS,
                        Source.EVENT, EnvironmentContextMode.ALWAYS, Gate.EVENT_MASTER));
    }

    private static EnvironmentContextOption random(String key) {
        return new EnvironmentContextOption(key, Source.TLM_RANDOM,
                EnvironmentContextMode.RANDOM, Gate.PROVIDER);
    }

    private static EnvironmentContextOption prompt(String key) {
        return new EnvironmentContextOption(key, Source.TLM_PROMPT,
                EnvironmentContextMode.ALWAYS, Gate.PROVIDER);
    }

    /** 饰品栏条目：本 mod 直接读取，不走 TLM 提供者 */
    private static EnvironmentContextOption accessory(String key, Gate gate) {
        return new EnvironmentContextOption(key, Source.ACCESSORY,
                EnvironmentContextMode.RANDOM, gate);
    }

    /** 目录（固定 37 项，顺序即界面与渲染顺序） */
    public static final List<EnvironmentContextOption> ALL = buildAll();

    private static final Map<String, EnvironmentContextOption> BY_KEY = buildIndex();

    private static Map<String, EnvironmentContextOption> buildIndex() {
        Map<String, EnvironmentContextOption> map = new LinkedHashMap<>();
        for (EnvironmentContextOption option : ALL) {
            map.put(option.key(), option);
        }
        return Map.copyOf(map);
    }

    /** 按 key 查目录项，目录外 key 返回 null */
    public static EnvironmentContextOption byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    // ===== 事件条目与事件种类的对应（仅这五项） =====

    public static final String KEY_DEATHS = "maid_self_talk_recent_deaths";
    public static final String KEY_PLAYER_HURT = "maid_self_talk_recent_player_hurt";
    public static final String KEY_SELF_HURT = "maid_self_talk_recent_self_hurt";
    public static final String KEY_ON_FIRE = "maid_self_talk_self_on_fire";
    public static final String KEY_DROWNING = "maid_self_talk_self_drowning";
    public static final String KEY_IDENTITY = "maid_self_talk_nearby_maid_identities";
    public static final String KEY_SELF_REVIVE = "maid_self_talk_recent_self_revive";
    public static final String KEY_SELF_KILLS = "maid_self_talk_recent_self_kills";

    // ===== 饰品条目 key =====

    public static final String KEY_MAID_BAUBLES = "maid_self_talk_maid_baubles";
    public static final String KEY_MAID_CURIOS = "maid_self_talk_maid_curios";
    public static final String KEY_USER_CURIOS = "maid_self_talk_user_curios";

    /** 是否为饰品条目 key（三个固定 key，且不依赖 {@code user_} 前缀判断） */
    static boolean isAccessoryKey(String key) {
        return KEY_MAID_BAUBLES.equals(key) || KEY_MAID_CURIOS.equals(key) || KEY_USER_CURIOS.equals(key);
    }

    /** 复杂列表条目：这些 key 走类型化快照，取值已是完整句子，不做反拆 */
    static boolean isComplexKey(String key) {
        return switch (key) {
            case "mainhand_item", "offhand_item", "inventory_items", "armor_items",
                 "user_mainhand", "user_armor", "effects",
                 "nearby_entities", KEY_IDENTITY -> true;
            default -> false;
        };
    }

    /** 事件条目 key → 事件种类；非事件条目返回 null */
    static SelfTalkEventBuffer.Kind eventKindOf(String key) {
        if (KEY_DEATHS.equals(key)) {
            return SelfTalkEventBuffer.Kind.DEATH;
        }
        if (KEY_PLAYER_HURT.equals(key)) {
            return SelfTalkEventBuffer.Kind.PLAYER_HURT;
        }
        if (KEY_SELF_HURT.equals(key)) {
            return SelfTalkEventBuffer.Kind.SELF_HURT;
        }
        if (KEY_SELF_REVIVE.equals(key)) {
            return SelfTalkEventBuffer.Kind.SELF_REVIVED;
        }
        if (KEY_SELF_KILLS.equals(key)) {
            return SelfTalkEventBuffer.Kind.SELF_KILL;
        }
        return null;
    }

    /** 事件种类 → 事件条目 key */
    static String keyOfEventKind(SelfTalkEventBuffer.Kind kind) {
        return switch (kind) {
            case DEATH -> KEY_DEATHS;
            case PLAYER_HURT -> KEY_PLAYER_HURT;
            case SELF_HURT -> KEY_SELF_HURT;
            case SELF_REVIVED -> KEY_SELF_REVIVE;
            case SELF_KILL -> KEY_SELF_KILLS;
        };
    }
}
