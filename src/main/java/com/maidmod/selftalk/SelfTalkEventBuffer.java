package com.maidmod.selftalk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 女仆感知事件缓冲：结构化保存死亡、玩家受伤与自身受伤记录，
 * 负责固定采样冷却、有效期淘汰、容量收缩与自然语言归并。
 * <p>
 * 不依赖 Minecraft / TLM 类型，也不读取配置——容量与有效期由调用方按当前配置传入，
 * 因此可脱离游戏直接自检（见 {@code src/test/java/.../SelfTalkEventBufferTest.java}）。
 * <p>
 * 时间一律用服务器全局 tick（{@code server.getTickCount()}）折算，20 tick = 1 秒；
 * 不用各维度 {@code gameTime}（各维度独立计数，跨维度比较会出现负差）。
 * <p>
 * 线程约束：仅在服务端主线程访问，与 {@link SelfTalkState} 同一约束。
 */
final class SelfTalkEventBuffer {

    /**
     * 受伤采样冷却（tick）：固定 1 秒，玩家受伤与自身受伤各用一个独立计时器。
     * <p>
     * 这是<b>采样</b>限制而非事件语义：冷却内的受击不会留下记录，
     * 所以缓冲里的条数不能反推实际受击次数，对外也不得输出精确次数。
     */
    static final long HURT_COOLDOWN_TICKS = 20L;

    /** 事件种类（死亡不参与受伤冷却与有效期） */
    enum Kind {
        DEATH,
        PLAYER_HURT,
        SELF_HURT
    }

    /**
     * 一条已发生的事实。
     * <p>
     * {@code subjectId} / {@code attackerId} 只用于内部比较与归并，绝不输出；
     * {@code text} 是采集时生成并清洗过的自然语言正文，不含任何时间前缀、编号或段标签；
     * 不保存实体、世界、DamageSource 等引用（避免长期持有已卸载实体的对象）。
     */
    record Event(
            Kind kind,
            long tick,
            UUID subjectId,
            UUID attackerId,
            String damageTypeId,
            String text) {
    }

    /** 按真实记录顺序保存的事件（容量由调用方按当前配置收缩） */
    private final Deque<Event> events = new ArrayDeque<>();
    /** 上次成功记录玩家受伤的 tick（-1 = 从未记录，保证首次必过冷却） */
    private long lastPlayerHurtTick = -1;
    /** 上次成功记录自身受伤的 tick（-1 = 从未记录） */
    private long lastSelfHurtTick = -1;

    /**
     * 追加一条事件。
     * <p>
     * 顺序固定为：过期淘汰 → 容量收缩 → 重复投递忽略 → 受伤冷却 → 容量溢出丢弃 → 入队。
     * 冷却只拦「记录」不拦「更新」：被冷却拦下的事件不刷新上次成功记录的 tick，
     * 因此连续受击不会把采样间隔无上限地往后推。
     *
     * @param event           事件（其 tick 即本次「现在」，用于过期计算）
     * @param maxEvents       当前容量上限（&lt; 1 时视为非法，跳过本次追加而非清空已有缓冲）
     * @param hurtMaxAgeTicks 受伤有效期（tick，达到即过期）
     * @return 是否真的记录
     */
    boolean append(Event event, int maxEvents, long hurtMaxAgeTicks) {
        if (event == null || maxEvents < 1) {
            return false;
        }
        long nowTick = event.tick();
        purgeExpired(nowTick, hurtMaxAgeTicks);
        shrink(maxEvents);

        Event last = events.peekLast();
        if (last != null && sameEvent(last, event)) {
            // 同一事件的重复投递（如 Player.die 与 super.die 先后各投递一次）只记一次
            return false;
        }
        if (!passesCooldown(event, nowTick)) {
            return false;
        }
        while (events.size() >= maxEvents) {
            events.pollFirst();
        }
        events.addLast(event);
        if (event.kind() == Kind.PLAYER_HURT) {
            lastPlayerHurtTick = nowTick;
        } else if (event.kind() == Kind.SELF_HURT) {
            lastSelfHurtTick = nowTick;
        }
        return true;
    }

    /**
     * 取出全部有效事件并清空队列（先过期淘汰、再容量收缩）。
     * <p>
     * 清空队列<b>不重置</b>两个受伤冷却计时器：消费是「读走已记录的事」，
     * 与采样间隔无关，否则一次派发就能把采样率放大到每 tick 一次。
     *
     * @param nowTick         当前服务器 tick
     * @param maxEvents       当前容量上限
     * @param hurtMaxAgeTicks 受伤有效期（tick）
     */
    List<Event> drain(long nowTick, int maxEvents, long hurtMaxAgeTicks) {
        purgeExpired(nowTick, hurtMaxAgeTicks);
        if (maxEvents >= 1) {
            shrink(maxEvents);
        }
        if (events.isEmpty()) {
            return List.of();
        }
        List<Event> drained = new ArrayList<>(events);
        events.clear();
        return drained;
    }

    /**
     * 把有效事件归并为自然语言短段（按记录顺序，不输出编号/项目符号/字段名）。
     * <p>
     * 只归并<b>相邻</b>且主体、攻击者、伤害类型、正文全相同的受伤记录：中间夹了别的事件就不合并，
     * 保证事件先后关系不被打乱。死亡不归并。归并只发生在渲染阶段，不刷新缓冲记录的时间，
     * 旧事件不会借新事件无限续期。过期记录在 {@link #drain} 阶段已被剔除，不参与「反复发生」的判断。
     * <p>
     * 归并时只说明「不止一次」，绝不输出精确受击次数——冷却期内的受击本就没有记录，
     * 输出条数会被误读为真实受击次数。
     */
    static String describe(List<Event> events, long nowTick) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int index = 0;
        while (index < events.size()) {
            Event first = events.get(index);
            int end = index + 1;
            while (first.kind() != Kind.DEATH && end < events.size()
                    && sameHurt(events.get(end), first)) {
                end++;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (end - index > 1) {
                sb.append("这段时间里，").append(first.text())
                        .append("，不止一次，最近一次").append(relativeTime(events.get(end - 1).tick(), nowTick)).append('。');
            } else {
                sb.append(relativeTime(first.tick(), nowTick)).append('，')
                        .append(first.text()).append('。');
            }
            index = end;
        }
        return sb.toString();
    }

    /** 全字段一致的重复投递判定（不同 UUID 的同名对象 tick 不同，不会被误判为同一条） */
    private static boolean sameEvent(Event a, Event b) {
        return a.kind() == b.kind() && a.tick() == b.tick()
                && sameHurt(a, b);
    }

    /** 伤害记录的可归并字段：种类、主体、攻击者、伤害类型、正文 */
    private static boolean sameHurt(Event a, Event b) {
        return a.kind() == b.kind()
                && Objects.equals(a.subjectId(), b.subjectId())
                && Objects.equals(a.attackerId(), b.attackerId())
                && Objects.equals(a.damageTypeId(), b.damageTypeId())
                && Objects.equals(a.text(), b.text());
    }

    /** 受伤冷却：首次必定通过，距上次成功记录不足一个采样间隔则忽略 */
    private boolean passesCooldown(Event event, long nowTick) {
        return switch (event.kind()) {
            case DEATH -> true;
            case PLAYER_HURT -> lastPlayerHurtTick < 0
                    || nowTick - lastPlayerHurtTick >= HURT_COOLDOWN_TICKS;
            case SELF_HURT -> lastSelfHurtTick < 0
                    || nowTick - lastSelfHurtTick >= HURT_COOLDOWN_TICKS;
        };
    }

    /** 受伤记录有效期：达到有效期即过期；死亡不设有效期（由容量、消费与女仆生命周期控制） */
    private void purgeExpired(long nowTick, long hurtMaxAgeTicks) {
        if (events.isEmpty()) {
            return;
        }
        events.removeIf(event -> event.kind() != Kind.DEATH
                && nowTick - event.tick() >= hurtMaxAgeTicks);
    }

    /** 容量收缩：容量被调小时在下一次入队/消费时收敛（不主动扫描，也不丢死亡优先保留） */
    private void shrink(int maxEvents) {
        while (events.size() > maxEvents) {
            events.pollFirst();
        }
    }

    /** 相对时间前缀：不足一个采样间隔说「刚才」，其余按秒取整 */
    private static String relativeTime(long tick, long nowTick) {
        long ageTicks = nowTick - tick;
        if (ageTicks < HURT_COOLDOWN_TICKS) {
            return "刚才";
        }
        return "约 " + (ageTicks / 20L) + " 秒前";
    }
}
