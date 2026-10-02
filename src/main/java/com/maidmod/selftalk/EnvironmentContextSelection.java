package com.maidmod.selftalk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * 环境上下文抽样（纯函数，不依赖 Minecraft / TLM，可直接自检）。
 * <p>
 * 规则（issue #14 锁定口径，不得自行扩展）：
 * <ul>
 *   <li>{@code always} = 本轮可用且模式为 ALWAYS 的全部条目；</li>
 *   <li>{@code pool} = 本轮可用且模式为 RANDOM 的全部条目；NEVER 不进入二者；</li>
 *   <li>pool 非空时抽 1~3 项（不足则全取），<b>无放回</b>；pool 为空时一次随机数都不取，
 *       绝不调用 {@code nextInt(0)}；</li>
 *   <li>结果 = always ∪ 随机选中集合。</li>
 * </ul>
 * 明确不做的事：不按分类各抽一次、不为事件另开随机池、不把每条伤害记录当作一个名额、
 * 不为 always 设数量上限、不对候选做第二次独立概率判定。
 * <p>
 * 返回集合是无序判定结果，调用方按 {@link EnvironmentContextOption#ALL} 的目录顺序渲染，
 * 保证同一次抽样的输出顺序稳定。
 */
public final class EnvironmentContextSelection {

    /** 单次请求从随机池抽取的数量区间（1~3，闭区间） */
    static final int MIN_RANDOM_PICKS = 1;
    static final int MAX_RANDOM_PICKS = 3;
    /** {@code nextInt(3)} 后 +1 得到 1~3 */
    private static final int RANDOM_BOUND = MAX_RANDOM_PICKS - MIN_RANDOM_PICKS + 1;

    private EnvironmentContextSelection() {
    }

    /**
     * 执行一次全局抽样。
     *
     * @param availableKeys 本轮可用（未被功能门、NEVER 与「无数据」排除）的条目 key；可含重复，内部去重
     * @param modeOf        条目 key → 该女仆对应的有效模式
     * @param random        {@code nextInt(bound)} 语义的随机源（游戏侧传 {@code maid.getRandom()::nextInt}）
     * @return 本次选中的条目 key 集合
     */
    public static Set<String> select(List<String> availableKeys,
                                     Map<String, EnvironmentContextMode> modeOf,
                                     IntUnaryOperator random) {
        Set<String> selected = new LinkedHashSet<>();
        if (availableKeys == null || availableKeys.isEmpty()) {
            return selected;
        }
        // 去重独立于 selected：同一 key 重复出现（如同一批多条死亡记录按类型投递多次）
        // 只能算一个候选，绝不能因此占据多个随机名额
        Set<String> seen = new HashSet<>();
        List<String> pool = new ArrayList<>();
        for (String key : availableKeys) {
            if (key == null || !seen.add(key)) {
                continue;
            }
            EnvironmentContextMode mode = modeOf == null ? null : modeOf.get(key);
            if (mode == EnvironmentContextMode.ALWAYS) {
                selected.add(key);
            } else if (mode == EnvironmentContextMode.RANDOM) {
                pool.add(key);
            }
            // NEVER 与「模式缺失」都既不进 always 也不进 pool
        }
        if (pool.isEmpty()) {
            return selected;
        }
        int count = Math.min(MIN_RANDOM_PICKS + random.applyAsInt(RANDOM_BOUND), pool.size());
        for (int i = 0; i < count; i++) {
            selected.add(pool.remove(random.applyAsInt(pool.size())));
        }
        return selected;
    }
}
