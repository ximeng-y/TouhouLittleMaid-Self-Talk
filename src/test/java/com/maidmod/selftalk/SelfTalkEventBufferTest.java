package com.maidmod.selftalk;

import java.util.List;
import java.util.UUID;

/**
 * {@link SelfTalkEventBuffer} 的自检入口（无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * 缓冲类刻意不依赖 Minecraft / TLM 类型，本类因此可以用 {@code javac + java} 单独编译执行，
 * 不必启动游戏：
 * <pre>
 * javac -d out src/main/java/com/maidmod/selftalk/ContextLanguage.java \
 *       src/main/java/com/maidmod/selftalk/SelfTalkPrompts.java \
 *       src/main/java/com/maidmod/selftalk/EnvironmentContextMode.java \
 *       src/main/java/com/maidmod/selftalk/EnvironmentContextOption.java \
 *       src/main/java/com/maidmod/selftalk/SelfTalkEventBuffer.java \
 *       src/main/java/com/maidmod/selftalk/EnvironmentContextRenderer.java \
 *       src/test/java/com/maidmod/selftalk/SelfTalkEventBufferTest.java
 * java -cp out com.maidmod.selftalk.SelfTalkEventBufferTest
 * </pre>
 * 覆盖冷却粒度、有效期边界、容量收缩、重复投递、去重与归并规则。
 * 事件正文的成句与归并分组由 {@link EnvironmentContextRendererTest} 覆盖，本类只断言
 * 「哪些记录进了分组、时间取了哪一条」这类生命周期行为。
 * Gradle 的 {@code test} 任务不含 JUnit 用例，不会执行本入口，需显式调用。
 */
public final class SelfTalkEventBufferTest {

    /** 默认有效期：60 秒 × 20 tick */
    private static final long MAX_AGE = 60L * 20L;
    /** 默认容量 */
    private static final int MAX_EVENTS = 5;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID ZOMBIE = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID SKELETON = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private static int checks = 0;

    private SelfTalkEventBufferTest() {
    }

    public static void main(String[] args) {
        hurtCooldownBoundaries();
        cooldownTimersAreIndependent();
        drainDoesNotResetCooldown();
        hurtMaxAgeBoundary();
        expiredHurtPurgedDeathKept();
        shrinkingMaxAgeDoesNotRestore();
        capacityOverflowDropsOldest();
        capacityShrinkAppliesOnNextUse();
        duplicateDeliveryRecordedOnce();
        sameNameDifferentUuidNotMerged();
        adjacentSameSourceMerged();
        differentSourceNotMergedAcrossMiddle();
        expiredEntryNotMergedIntoRepeat();
        drainIsConsuming();
        deathIsNeverExpired();
        System.out.println("SelfTalkEventBufferTest: all " + checks + " checks passed");
    }

    /** 首次记录必过；同类伤害 tick 0、19、20 只记 0 与 20 */
    private static void hurtCooldownBoundaries() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        check(buffer.append(selfHurt(0), MAX_EVENTS, MAX_AGE), "首次受伤应可记录");
        check(!buffer.append(selfHurt(19), MAX_EVENTS, MAX_AGE), "19 tick 处应被冷却拦下");
        check(buffer.append(selfHurt(20), MAX_EVENTS, MAX_AGE), "20 tick 处应可记录");
        check(buffer.drain(20, MAX_EVENTS, MAX_AGE).size() == 2, "应恰好记录 2 条");
    }

    /** 玩家受伤与自身受伤各用一个计时器，同 tick 互不影响 */
    private static void cooldownTimersAreIndependent() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        check(buffer.append(playerHurt(0, ALICE, ZOMBIE), MAX_EVENTS, MAX_AGE), "玩家受伤首次记录");
        check(buffer.append(selfHurt(0), MAX_EVENTS, MAX_AGE), "同 tick 自身受伤不应被玩家冷却拦下");
        check(!buffer.append(playerHurt(10, ALICE, ZOMBIE), MAX_EVENTS, MAX_AGE), "玩家冷却仍生效");
        check(!buffer.append(selfHurt(10), MAX_EVENTS, MAX_AGE), "自身冷却仍生效");
    }

    /** 消费不重置采样间隔：drain 后立刻受伤仍被冷却拦下 */
    private static void drainDoesNotResetCooldown() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(selfHurt(0), MAX_EVENTS, MAX_AGE);
        buffer.drain(1, MAX_EVENTS, MAX_AGE);
        check(!buffer.append(selfHurt(5), MAX_EVENTS, MAX_AGE), "消费不应重置冷却");
        check(buffer.append(selfHurt(20), MAX_EVENTS, MAX_AGE), "距上次记录满 20 tick 后应放行");
    }

    /** 有效期边界：年龄 1199 保留，1200 淘汰（达到即过期） */
    private static void hurtMaxAgeBoundary() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(selfHurt(0), MAX_EVENTS, MAX_AGE);
        check(buffer.drain(1199, MAX_EVENTS, MAX_AGE).size() == 1, "年龄 1199 tick 应保留");

        SelfTalkEventBuffer other = new SelfTalkEventBuffer();
        other.append(selfHurt(0), MAX_EVENTS, MAX_AGE);
        check(other.drain(1200, MAX_EVENTS, MAX_AGE).isEmpty(), "年龄 1200 tick 应淘汰");
    }

    /** 混合缓冲：过期的受伤被淘汰，死亡记录不受有效期影响 */
    private static void expiredHurtPurgedDeathKept() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(death(0, ALICE), MAX_EVENTS, MAX_AGE);
        buffer.append(selfHurt(100), MAX_EVENTS, MAX_AGE);
        List<SelfTalkEventBuffer.Event> events = buffer.drain(2000, MAX_EVENTS, MAX_AGE);
        check(events.size() == 1 && events.get(0).kind() == SelfTalkEventBuffer.Kind.DEATH,
                "过期受伤应淘汰、死亡应保留");
    }

    /** 有效期调小：按新值淘汰；之后调大不能恢复已删数据 */
    private static void shrinkingMaxAgeDoesNotRestore() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(selfHurt(0), MAX_EVENTS, MAX_AGE);
        check(buffer.drain(300, MAX_EVENTS, 200).isEmpty(), "按调小后的有效期应淘汰");
        check(buffer.drain(300, MAX_EVENTS, MAX_AGE).isEmpty(), "调大有效期不能恢复已淘汰数据");
    }

    /** 容量溢出丢最旧 */
    private static void capacityOverflowDropsOldest() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        for (int i = 0; i < 6; i++) {
            // 间隔 20 tick 以绕开采样冷却；主体各不相同，避免被重复投递判定吞掉
            buffer.append(death(i * 20L, UUID.randomUUID()), MAX_EVENTS, MAX_AGE);
        }
        List<SelfTalkEventBuffer.Event> events = buffer.drain(100, MAX_EVENTS, MAX_AGE);
        check(events.size() == MAX_EVENTS, "应保持容量上限");
        check(events.get(0).tick() == 20L, "应丢弃最旧的一条");
    }

    /** 容量从 5 改成 2：下一次入队或消费时收缩 */
    private static void capacityShrinkAppliesOnNextUse() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        for (int i = 0; i < 5; i++) {
            buffer.append(death(i * 20L, UUID.randomUUID()), MAX_EVENTS, MAX_AGE);
        }
        List<SelfTalkEventBuffer.Event> drained = buffer.drain(100, 2, MAX_AGE);
        check(drained.size() == 2, "消费时应按新容量收缩");

        SelfTalkEventBuffer other = new SelfTalkEventBuffer();
        for (int i = 0; i < 5; i++) {
            other.append(death(i * 20L, UUID.randomUUID()), MAX_EVENTS, MAX_AGE);
        }
        other.append(death(200, ALICE), 2, MAX_AGE);
        List<SelfTalkEventBuffer.Event> afterAppend = other.drain(100, 2, MAX_AGE);
        check(afterAppend.size() == 2, "入队时应按新容量收缩");
        check(afterAppend.get(1).tick() == 200L, "新事件应在末尾");
    }

    /** 同一死亡事件同 tick 重复投递只记一次 */
    private static void duplicateDeliveryRecordedOnce() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        check(buffer.append(death(50, ALICE), MAX_EVENTS, MAX_AGE), "首次投递应记录");
        check(!buffer.append(death(50, ALICE), MAX_EVENTS, MAX_AGE), "重复投递应忽略");
        check(buffer.drain(50, MAX_EVENTS, MAX_AGE).size() == 1, "只应记录一次");
    }

    /** 同名但 UUID 不同：既不误去重也不误归并 */
    private static void sameNameDifferentUuidNotMerged() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(death(0, ALICE), MAX_EVENTS, MAX_AGE);
        check(buffer.append(death(0, BOB), MAX_EVENTS, MAX_AGE),
                "同名不同 UUID 的同 tick 死亡不应被判为重复投递");
        List<SelfTalkEventBuffer.Event> events = buffer.drain(0, MAX_EVENTS, MAX_AGE);
        check(events.size() == 2, "两条都应保留");
        check(SelfTalkEventBuffer.group(events).size() == 2, "同名不同主体不应归并为一条");
    }

    /** 相邻同来源受伤归并，且不输出精确受击次数 */
    private static void adjacentSameSourceMerged() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(selfHurt(0), MAX_EVENTS, MAX_AGE);
        buffer.append(selfHurt(20), MAX_EVENTS, MAX_AGE);
        List<SelfTalkEventBuffer.Event> events = buffer.drain(1000, MAX_EVENTS, MAX_AGE);
        List<SelfTalkEventBuffer.Group> groups = SelfTalkEventBuffer.group(events);
        check(groups.size() == 1 && groups.get(0).repeated(), "相邻同来源应归并为一组");
        check(groups.get(0).last().tick() == 20L, "组内最后一条的 tick 用于「最近一次」");
        String text = EnvironmentContextRenderer.renderEvents(events, 1000, ContextLanguage.ZH);
        check(text.contains("多次"), "相邻同来源应说「多次」");
        check(!text.contains("2 次") && !text.contains("2次"), "不得输出精确受击次数");
    }

    /** A、B、A 三段不同来源：不跨中间事件合并 A */
    private static void differentSourceNotMergedAcrossMiddle() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(selfHurt(0), MAX_EVENTS, MAX_AGE);
        buffer.append(playerHurt(20, ALICE, SKELETON), MAX_EVENTS, MAX_AGE);
        buffer.append(selfHurt(40), MAX_EVENTS, MAX_AGE);
        List<SelfTalkEventBuffer.Event> events = buffer.drain(1000, MAX_EVENTS, MAX_AGE);
        List<SelfTalkEventBuffer.Group> groups = SelfTalkEventBuffer.group(events);
        check(groups.size() == 3, "三条非相邻记录应各自成组");
        check(groups.stream().noneMatch(SelfTalkEventBuffer.Group::repeated), "不应出现归并组");
    }

    /** 归并前其中一条已过期：只渲染有效记录 */
    private static void expiredEntryNotMergedIntoRepeat() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(death(0, ALICE), MAX_EVENTS, MAX_AGE);
        buffer.append(selfHurt(0), MAX_EVENTS, MAX_AGE);
        // 在 1200 tick 处再受一次伤：旧的自身受伤已过期被淘汰，本次不与它归并
        buffer.append(selfHurt(1200), MAX_EVENTS, MAX_AGE);
        List<SelfTalkEventBuffer.Event> events = buffer.drain(1200, MAX_EVENTS, MAX_AGE);
        check(SelfTalkEventBuffer.group(events).stream().noneMatch(SelfTalkEventBuffer.Group::repeated),
                "过期记录不应参与反复发生的判断");
        check(events.stream().anyMatch(e -> e.kind() == SelfTalkEventBuffer.Kind.DEATH), "死亡记录仍在");
    }

    /** 重复消费：第二次为空 */
    private static void drainIsConsuming() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(death(0, ALICE), MAX_EVENTS, MAX_AGE);
        check(buffer.drain(0, MAX_EVENTS, MAX_AGE).size() == 1, "首次消费应有内容");
        check(buffer.drain(0, MAX_EVENTS, MAX_AGE).isEmpty(), "再次消费应为空");
    }

    /** 死亡记录不套用受伤的 60 秒有效期 */
    private static void deathIsNeverExpired() {
        SelfTalkEventBuffer buffer = new SelfTalkEventBuffer();
        buffer.append(death(0, ALICE), MAX_EVENTS, MAX_AGE);
        check(buffer.drain(10_000_000L, MAX_EVENTS, MAX_AGE).size() == 1, "死亡记录不应因时间过期");
    }

    private static SelfTalkEventBuffer.Event death(long tick, UUID subject) {
        return new SelfTalkEventBuffer.Event(SelfTalkEventBuffer.Kind.DEATH, tick,
                subject, ZOMBIE, "minecraft:player_attack",
                new SelfTalkEventBuffer.DeathFact("某只生物死了", "something died"));
    }

    private static SelfTalkEventBuffer.Event playerHurt(long tick, UUID subject, UUID attacker) {
        return new SelfTalkEventBuffer.Event(SelfTalkEventBuffer.Kind.PLAYER_HURT, tick,
                subject, attacker, "minecraft:mob_attack",
                new SelfTalkEventBuffer.HurtFact(SelfTalkEventBuffer.Subject.OWNER, "Alice", "Skeleton",
                        SelfTalkEventBuffer.Source.ATTACKER));
    }

    private static SelfTalkEventBuffer.Event selfHurt(long tick) {
        return new SelfTalkEventBuffer.Event(SelfTalkEventBuffer.Kind.SELF_HURT, tick,
                BOB, ZOMBIE, "minecraft:mob_attack",
                new SelfTalkEventBuffer.HurtFact(SelfTalkEventBuffer.Subject.SELF, null, "Zombie",
                        SelfTalkEventBuffer.Source.ATTACKER));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("检查失败：" + message);
        }
    }
}
