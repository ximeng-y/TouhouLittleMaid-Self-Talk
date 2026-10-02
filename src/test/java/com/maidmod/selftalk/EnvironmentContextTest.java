package com.maidmod.selftalk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 环境上下文目录与抽样自检入口（无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * {@link EnvironmentContextOption}/{@link EnvironmentContextMode}/{@link EnvironmentContextSelection}
 * 刻意不依赖 Minecraft / TLM 类型，本类因此可以用 {@code javac + java} 单独编译执行，不必启动游戏：
 * <pre>
 * javac -d out src/main/java/com/maidmod/selftalk/EnvironmentContextMode.java \
 *       src/main/java/com/maidmod/selftalk/EnvironmentContextOption.java \
 *       src/main/java/com/maidmod/selftalk/EnvironmentContextSelection.java \
 *       src/main/java/com/maidmod/selftalk/SelfTalkEventBuffer.java \
 *       src/test/java/com/maidmod/selftalk/EnvironmentContextTest.java
 * java -cp out com.maidmod.selftalk.EnvironmentContextTest
 * </pre>
 * Gradle 的 {@code test} 任务不含 JUnit 用例，不会执行本入口，需显式调用。
 */
public final class EnvironmentContextTest {

    private static int checks = 0;

    private EnvironmentContextTest() {
    }

    public static void main(String[] args) {
        catalogShape();
        modeCycleAndParsing();
        emptyPoolNeverDrawsRandom();
        poolSizeRespected();
        selectionInvariants();
        eventKindCountsAsOneCandidate();
        unavailableEntriesDoNotTakeSlots();
        overrideDecisionMatchesDefaultMode();
        System.out.println("EnvironmentContextTest passed: " + checks + " checks");
    }

    /** 1. 32 个 key 唯一，15 项默认随机、17 项默认必定进入，且不含默认 NEVER */
    private static void catalogShape() {
        List<EnvironmentContextOption> all = EnvironmentContextOption.ALL;
        check(all.size() == 32, "目录应为 32 项，实际 " + all.size());
        Set<String> keys = new LinkedHashSet<>();
        int randomDefault = 0;
        int alwaysDefault = 0;
        for (EnvironmentContextOption option : all) {
            check(keys.add(option.key()), "key 重复：" + option.key());
            if (option.defaultMode() == EnvironmentContextMode.RANDOM) {
                randomDefault++;
            } else if (option.defaultMode() == EnvironmentContextMode.ALWAYS) {
                alwaysDefault++;
            } else {
                check(false, "默认模式不应为 NEVER：" + option.key());
            }
            check(EnvironmentContextOption.byKey(option.key()) == option, "byKey 回查失败：" + option.key());
        }
        check(randomDefault == 15, "默认随机应为 15 项，实际 " + randomDefault);
        check(alwaysDefault == 17, "默认必定进入应为 17 项，实际 " + alwaysDefault);
        check(EnvironmentContextOption.byKey("not_in_catalog") == null, "目录外 key 应返回 null");
        check(EnvironmentContextOption.byKey(null) == null, "null key 应返回 null");
    }

    /** 2. 三态循环顺序与字符串转换 */
    private static void modeCycleAndParsing() {
        check(EnvironmentContextMode.RANDOM.next() == EnvironmentContextMode.ALWAYS, "RANDOM→ALWAYS");
        check(EnvironmentContextMode.ALWAYS.next() == EnvironmentContextMode.NEVER, "ALWAYS→NEVER");
        check(EnvironmentContextMode.NEVER.next() == EnvironmentContextMode.RANDOM, "NEVER→RANDOM");
        for (EnvironmentContextMode mode : EnvironmentContextMode.values()) {
            check(EnvironmentContextMode.fromId(mode.id()) == mode, "id 回解失败：" + mode.id());
        }
        check("random".equals(EnvironmentContextMode.RANDOM.id()), "RANDOM 存储值");
        check("always".equals(EnvironmentContextMode.ALWAYS.id()), "ALWAYS 存储值");
        check("never".equals(EnvironmentContextMode.NEVER.id()), "NEVER 存储值");
        check(EnvironmentContextMode.fromId("RANDOM") == null, "大小写敏感：非法值返回 null");
        check(EnvironmentContextMode.fromId("") == null, "空串返回 null");
        check(EnvironmentContextMode.fromId(null) == null, "null 返回 null");
    }

    /** 3. 随机池为空时一次随机数都不取（防 nextInt(0) 抛异常） */
    private static void emptyPoolNeverDrawsRandom() {
        AtomicInteger calls = new AtomicInteger();
        Set<String> picked = EnvironmentContextSelection.select(
                List.of("healthy", "weather"),
                Map.of("healthy", EnvironmentContextMode.NEVER, "weather", EnvironmentContextMode.NEVER),
                bound -> {
                    calls.incrementAndGet();
                    return 0;
                });
        check(picked.isEmpty(), "全 NEVER 时不应选中任何项");
        check(calls.get() == 0, "随机池为空时不应取随机数，实际取了 " + calls.get() + " 次");

        calls.set(0);
        picked = EnvironmentContextSelection.select(List.of(), Map.of(), bound -> {
            calls.incrementAndGet();
            return 0;
        });
        check(picked.isEmpty() && calls.get() == 0, "空候选集不应取随机数");

        // 随机池为空但 always 非空：只返回 always，仍不取随机数
        calls.set(0);
        picked = EnvironmentContextSelection.select(
                List.of("healthy", "game_time"),
                Map.of("healthy", EnvironmentContextMode.ALWAYS,
                        "game_time", EnvironmentContextMode.NEVER),
                bound -> {
                    calls.incrementAndGet();
                    return 0;
                });
        check(picked.equals(Set.of("healthy")), "随机池为空时应只保留 always 项");
        check(calls.get() == 0, "随机池为空时不应取随机数（含 always 在场的分支）");
    }

    /** 4. 池内 1／2／3／超过 3 项时，结果数量为 min(1..3, poolSize) */
    private static void poolSizeRespected() {
        for (int poolSize = 1; poolSize <= 8; poolSize++) {
            List<String> pool = new ArrayList<>();
            Map<String, EnvironmentContextMode> modes = new HashMap<>();
            for (int i = 0; i < poolSize; i++) {
                String key = "k" + i;
                pool.add(key);
                modes.put(key, EnvironmentContextMode.RANDOM);
            }
            // 随机数取遍所有分片，确认数量恒落在 [min(1,poolSize), min(3,poolSize)]
            for (int draw = 0; draw < 3; draw++) {
                int finalDraw = draw;
                Set<String> picked = EnvironmentContextSelection.select(pool, modes, bound -> {
                    check(bound > 0, "nextInt 的 bound 必须为正，实际 " + bound);
                    return finalDraw % bound;
                });
                int expected = Math.min(1 + draw, poolSize);
                check(picked.size() == expected,
                        "池 " + poolSize + " 项、抽 " + (draw + 1) + " 时应选中 " + expected
                                + " 项，实际 " + picked.size());
            }
        }
    }

    /** 5. 结果无重复、无 NEVER、包含全部可用 ALWAYS */
    private static void selectionInvariants() {
        List<String> available = List.of(
                "healthy", "game_time", "nearby_entities", "user_name", "effects", "weather");
        Map<String, EnvironmentContextMode> modes = Map.of(
                "healthy", EnvironmentContextMode.ALWAYS,
                "game_time", EnvironmentContextMode.ALWAYS,
                "nearby_entities", EnvironmentContextMode.RANDOM,
                "user_name", EnvironmentContextMode.RANDOM,
                "effects", EnvironmentContextMode.NEVER,
                "weather", EnvironmentContextMode.NEVER);
        for (int draw = 0; draw < 3; draw++) {
            int finalDraw = draw;
            Set<String> picked = EnvironmentContextSelection.select(available, modes, bound -> finalDraw % bound);
            check(new ArrayList<>(picked).size() == picked.size(), "结果不应有重复");
            check(picked.contains("healthy") && picked.contains("game_time"), "必须包含全部可用 ALWAYS");
            check(!picked.contains("effects") && !picked.contains("weather"), "结果不应包含 NEVER");
            int randomPicked = picked.size() - 2;
            check(randomPicked == Math.min(1 + draw, 2),
                    "随机名额应为 min(抽数, 池 2)，实际 " + randomPicked);
        }
        // 随机序列：首个数决定抽几项（bound 固定为 3），之后每次的 bound 恰为剩余池大小（无放回递减）
        List<Integer> bounds = new ArrayList<>();
        EnvironmentContextSelection.select(List.of("a", "b", "c"),
                Map.of("a", EnvironmentContextMode.RANDOM, "b", EnvironmentContextMode.RANDOM,
                        "c", EnvironmentContextMode.RANDOM),
                bound -> {
                    bounds.add(bound);
                    return bound - 1;
                });
        check(bounds.equals(List.of(3, 3, 2, 1)),
                "抽取序列的 bound 应为 [3(抽数), 3, 2, 1(剩余池)]，实际 " + bounds);
    }

    /** 6. 事件类型按一个候选计数：同一 key 出现多次不增加权重，也不占多个名额 */
    private static void eventKindCountsAsOneCandidate() {
        Map<String, EnvironmentContextMode> modes = Map.of(
                EnvironmentContextOption.KEY_DEATHS, EnvironmentContextMode.RANDOM,
                "healthy", EnvironmentContextMode.ALWAYS);
        // 死亡条目传 5 次（模拟本批 5 条死亡记录）
        List<String> withDuplicates = List.of(
                EnvironmentContextOption.KEY_DEATHS, EnvironmentContextOption.KEY_DEATHS,
                EnvironmentContextOption.KEY_DEATHS, EnvironmentContextOption.KEY_DEATHS,
                EnvironmentContextOption.KEY_DEATHS, "healthy");
        Set<String> picked = EnvironmentContextSelection.select(withDuplicates, modes, bound -> 0);
        check(picked.equals(Set.of(EnvironmentContextOption.KEY_DEATHS, "healthy")),
                "重复投递的事件 key 应只计为一个候选，实际 " + picked);
        check(!picked.contains("__none__"), "不应产生虚拟候选");

        check(EnvironmentContextOption.eventKindOf(EnvironmentContextOption.KEY_DEATHS)
                        == SelfTalkEventBuffer.Kind.DEATH, "死亡条目 → DEATH");
        check(EnvironmentContextOption.eventKindOf(EnvironmentContextOption.KEY_PLAYER_HURT)
                        == SelfTalkEventBuffer.Kind.PLAYER_HURT, "玩家受伤条目 → PLAYER_HURT");
        check(EnvironmentContextOption.eventKindOf(EnvironmentContextOption.KEY_SELF_HURT)
                        == SelfTalkEventBuffer.Kind.SELF_HURT, "自身受伤条目 → SELF_HURT");
        check(EnvironmentContextOption.eventKindOf("healthy") == null, "非事件条目无事件种类");
        check(EnvironmentContextOption.eventKindOf(EnvironmentContextOption.KEY_ON_FIRE) == null,
                "着火是实时状态，不是事件");
        check(EnvironmentContextOption.eventKindOf(EnvironmentContextOption.KEY_DROWNING) == null,
                "缺氧是实时状态，不是事件");
        // 三个事件条目与三个事件种类一一对应
        Set<String> eventEntryKeys = new LinkedHashSet<>();
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            if (option.source() == EnvironmentContextOption.Source.EVENT) {
                eventEntryKeys.add(option.key());
                check(EnvironmentContextOption.eventKindOf(option.key()) != null,
                        "事件条目应有对应种类：" + option.key());
            }
        }
        check(eventEntryKeys.size() == 3, "事件条目应为 3 项，实际 " + eventEntryKeys.size());
        for (SelfTalkEventBuffer.Kind kind : SelfTalkEventBuffer.Kind.values()) {
            check(eventEntryKeys.contains(EnvironmentContextOption.keyOfEventKind(kind)),
                    "事件种类缺少对应条目：" + kind);
        }
    }

    /** 7. 禁用（功能门）或无实际数据的项不进入候选，因此不占抽样名额 */
    private static void unavailableEntriesDoNotTakeSlots() {
        // 池中 3 项，其中 2 项本轮无数据／被功能门排除，只剩 1 项可抽 → 只选 1 项
        List<String> available = List.of("a");
        Map<String, EnvironmentContextMode> modes = Map.of(
                "a", EnvironmentContextMode.RANDOM,
                "b", EnvironmentContextMode.RANDOM,
                "c", EnvironmentContextMode.RANDOM);
        Set<String> picked = EnvironmentContextSelection.select(available, modes, bound -> bound - 1);
        check(picked.equals(Set.of("a")), "不可用项不应占名额，实际 " + picked);

        // 全部不可用 → 空结果、不取随机数
        AtomicInteger calls = new AtomicInteger();
        picked = EnvironmentContextSelection.select(List.of(), modes, bound -> {
            calls.incrementAndGet();
            return 0;
        });
        check(picked.isEmpty() && calls.get() == 0, "无可用项时不应抽取");
    }

    /**
     * 8. 覆盖落盘判定：原固定项改为随机必须保存，恢复 always 删除覆盖，原随机项保持 random 删除覆盖。
     * 存储层用同一方法判定，本检查覆盖真实决策函数。
     */
    private static void overrideDecisionMatchesDefaultMode() {
        EnvironmentContextOption alwaysByDefault = EnvironmentContextOption.byKey("healthy");
        EnvironmentContextOption randomByDefault = EnvironmentContextOption.byKey("nearby_entities");
        check(alwaysByDefault != null && randomByDefault != null, "示例条目应存在于目录");
        check(alwaysByDefault.isOverride(EnvironmentContextMode.ALWAYS) == false,
                "原固定项保持 always 应删除覆盖");
        check(alwaysByDefault.isOverride(EnvironmentContextMode.RANDOM),
                "原固定项改为 random 必须保存覆盖");
        check(alwaysByDefault.isOverride(EnvironmentContextMode.NEVER),
                "原固定项改为 never 必须保存覆盖");
        check(randomByDefault.isOverride(EnvironmentContextMode.RANDOM) == false,
                "原随机项保持 random 应删除覆盖");
        check(randomByDefault.isOverride(EnvironmentContextMode.ALWAYS),
                "原随机项改为 always 必须保存覆盖");
        check(randomByDefault.isOverride(EnvironmentContextMode.NEVER),
                "原随机项改为 never 必须保存覆盖");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }
}
