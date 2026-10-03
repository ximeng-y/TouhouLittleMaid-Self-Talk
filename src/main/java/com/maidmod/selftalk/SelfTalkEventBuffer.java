package com.maidmod.selftalk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 女仆感知事件缓冲：结构化保存死亡、玩家受伤与自身受伤记录，
 * 负责固定采样冷却、有效期淘汰、容量收缩与相邻记录归并。
 * <p>
 * 不依赖 Minecraft / TLM 类型，也不读取配置——容量与有效期由调用方按当前配置传入，
 * 因此可脱离游戏直接自检（见 {@code src/test/java/.../SelfTalkEventBufferTest.java}）。
 * <p>
 * 记录保存的是<b>语言中立的事实</b>（主体类别、事发时取到的名称快照、来源分类），
 * 不是成句文本：入队后玩家切换聊天语言，仍按新语言的模板渲染，无需重新采集事件。
 * 名称与死亡正文在事发时取快照，避免实体离开后丢失来源；缓冲里<b>不</b>持有
 * {@code Entity}、{@code Level}、{@code DamageSource} 等活对象引用。
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

    /** 受伤主体类别（渲染时决定称呼，不保存实体引用） */
    enum Subject {
        /** 观察者自己（自身受伤） */
        SELF,
        /** 观察者的主人 */
        OWNER,
        /** 其它玩家 */
        PLAYER
    }

    /**
     * 伤害来源分类，与既有的优先级一一对应：
     * <ol>
     *   <li>{@link #ATTACKER}：{@code DamageSource.getEntity()} 能取得造成伤害者；</li>
     *   <li>{@link #FIRE}／{@link #DROWNING}／{@link #FALL}：无来源实体时按伤害类型标签分类；</li>
     *   <li>{@link #OTHER}：其余情况保留伤害类型标识，不猜原因。</li>
     * </ol>
     * 投射物实体不算攻击者（{@code getDirectEntity()} 是箭矢本身），
     * 也不新增武器、伤害数值或更细的来源推断。
     */
    enum Source {
        ATTACKER,
        FIRE,
        DROWNING,
        FALL,
        OTHER
    }

    /** 事件事实：语言中立，渲染时才按目标语言成句 */
    sealed interface Fact permits HurtFact, DeathFact {
    }

    /**
     * 受伤事实。
     *
     * @param subject      主体类别
     * @param subjectName  事发时主体名称（自己为 null）；已清洗过段标签与换行
     * @param attackerName 事发时攻击者名称；仅 {@link Source#ATTACKER} 非空
     * @param source       来源分类
     */
    record HurtFact(Subject subject, String subjectName, String attackerName, Source source) implements Fact {
    }

    /**
     * 死亡事实：保留原有死亡正文快照，并<b>显式</b>给出英文渲染结果。
     * <p>
     * 中文正文取事发时的原版死亡消息（沿用原行为）；英文正文由
     * {@link DeathMessageEnglishRenderer} 从同一份 {@code Component} 独立渲染，
     * 两者都在事发时定格，之后不再依赖游戏全局语言。
     */
    record DeathFact(String zhText, String enText) implements Fact {
    }

    /**
     * 一条已发生的事实。
     * <p>
     * {@code subjectId} / {@code attackerId} 只用于内部比较与归并，绝不输出；
     * {@code damageTypeId} 仅在无法归类时作为兜底标识输出。
     */
    record Event(
            Kind kind,
            long tick,
            UUID subjectId,
            UUID attackerId,
            String damageTypeId,
            Fact fact) {
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
     * 把有效事件归并为语言中立的连续分组，供 {@link EnvironmentContextRenderer} 按语言成句。
     * <p>
     * 只归并<b>相邻</b>且种类、主体、攻击者、伤害类型与事实内容全相同的受伤记录：中间夹了别的事件
     * 就不合并，保证事件先后关系不被打乱。死亡不归并。归并只发生在渲染阶段，不刷新缓冲里的记录时间，
     * 旧事件不会借新事件无限续期。过期记录在 {@link #drain} 阶段已被剔除，不参与「反复发生」的判断。
     * <p>
     * 归并比较的是语言中立的事实，不比最终中文或英文句子——切换语言不会改变分组结果。
     */
    static List<Group> group(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<Group> groups = new ArrayList<>();
        int index = 0;
        while (index < events.size()) {
            Event first = events.get(index);
            int end = index + 1;
            while (first.kind() != Kind.DEATH && end < events.size()
                    && sameHurt(events.get(end), first)) {
                end++;
            }
            groups.add(new Group(first, events.get(end - 1), end - index > 1));
            index = end;
        }
        return groups;
    }

    /**
     * 一组相邻同源记录。
     *
     * @param first    组内第一条（事实取它，组内事实相同）
     * @param last     组内最后一条（相对时间取它的 tick）
     * @param repeated 是否不止一条（对外只说「多次」，不输出精确次数）
     */
    record Group(Event first, Event last, boolean repeated) {
    }

    /** 全字段一致的重复投递判定（不同 UUID 的同名对象 tick 不同，不会被误判为同一条） */
    private static boolean sameEvent(Event a, Event b) {
        return a.kind() == b.kind() && a.tick() == b.tick()
                && sameHurt(a, b);
    }

    /** 伤害记录的可归并字段：种类、主体、攻击者、伤害类型、语言中立的事实内容 */
    private static boolean sameHurt(Event a, Event b) {
        return a.kind() == b.kind()
                && Objects.equals(a.subjectId(), b.subjectId())
                && Objects.equals(a.attackerId(), b.attackerId())
                && Objects.equals(a.damageTypeId(), b.damageTypeId())
                && Objects.equals(a.fact(), b.fact());
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
}
