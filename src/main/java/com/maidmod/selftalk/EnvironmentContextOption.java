package com.maidmod.selftalk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境上下文目录项（固定 32 项）。
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
        /** 环境事件（死亡／玩家受伤／自身受伤） */
        EVENT,
        /** 实时自身状态（着火／水下缺氧） */
        REALTIME
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
        IDENTITY_PROVIDER
    }

    /** 是否与目录默认模式不同（即是否需要落盘为玩家覆盖） */
    public boolean isOverride(EnvironmentContextMode mode) {
        return mode != defaultMode;
    }

    // ===== 目录（顺序即界面顺序） =====

    private static List<EnvironmentContextOption> buildAll() {
        return List.of(
                // 1~14：TLM 原随机信息，默认随机
                random("nearby_entities"),
                random("mainhand_item"),
                random("offhand_item"),
                random("inventory_items"),
                random("armor_items"),
                random("self_position"),
                random("user_position"),
                random("distance_to_user"),
                random("light_level"),
                random("user_name"),
                random("user_healthy"),
                random("user_mainhand"),
                random("user_armor"),
                random("effects"),
                // 15~22：TLM 固定信息 status，默认必定进入
                prompt("healthy"),
                prompt("sleep_state"),
                prompt("follow_state"),
                prompt("sitting"),
                prompt("riding"),
                prompt("schedule"),
                prompt("activity"),
                prompt("work_task"),
                // 23~26：TLM 固定信息 world，默认必定进入
                prompt("game_time"),
                prompt("weather"),
                prompt("dimension"),
                prompt("biome"),
                // 27：附近女仆身份，原附着于随机分类，默认随机
                new EnvironmentContextOption("maid_self_talk_nearby_maid_identities",
                        Source.TLM_RANDOM, EnvironmentContextMode.RANDOM, Gate.IDENTITY_PROVIDER),
                // 28~32：环境事件与实时自身状态，默认必定进入
                new EnvironmentContextOption("maid_self_talk_recent_deaths",
                        Source.EVENT, EnvironmentContextMode.ALWAYS, Gate.EVENT_MASTER),
                new EnvironmentContextOption("maid_self_talk_recent_player_hurt",
                        Source.EVENT, EnvironmentContextMode.ALWAYS, Gate.EVENT_HURT),
                new EnvironmentContextOption("maid_self_talk_recent_self_hurt",
                        Source.EVENT, EnvironmentContextMode.ALWAYS, Gate.EVENT_SELF),
                new EnvironmentContextOption("maid_self_talk_self_on_fire",
                        Source.REALTIME, EnvironmentContextMode.ALWAYS, Gate.EVENT_SELF),
                new EnvironmentContextOption("maid_self_talk_self_drowning",
                        Source.REALTIME, EnvironmentContextMode.ALWAYS, Gate.EVENT_SELF));
    }

    private static EnvironmentContextOption random(String key) {
        return new EnvironmentContextOption(key, Source.TLM_RANDOM,
                EnvironmentContextMode.RANDOM, Gate.PROVIDER);
    }

    private static EnvironmentContextOption prompt(String key) {
        return new EnvironmentContextOption(key, Source.TLM_PROMPT,
                EnvironmentContextMode.ALWAYS, Gate.PROVIDER);
    }

    /** 目录（固定 32 项，顺序即界面与渲染顺序） */
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

    // ===== 事件条目与事件种类的对应（仅这三项） =====

    public static final String KEY_DEATHS = "maid_self_talk_recent_deaths";
    public static final String KEY_PLAYER_HURT = "maid_self_talk_recent_player_hurt";
    public static final String KEY_SELF_HURT = "maid_self_talk_recent_self_hurt";
    public static final String KEY_ON_FIRE = "maid_self_talk_self_on_fire";
    public static final String KEY_DROWNING = "maid_self_talk_self_drowning";
    public static final String KEY_IDENTITY = "maid_self_talk_nearby_maid_identities";

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
        return null;
    }

    /** 事件种类 → 事件条目 key */
    static String keyOfEventKind(SelfTalkEventBuffer.Kind kind) {
        return switch (kind) {
            case DEATH -> KEY_DEATHS;
            case PLAYER_HURT -> KEY_PLAYER_HURT;
            case SELF_HURT -> KEY_SELF_HURT;
        };
    }
}
