package com.maidmod.selftalk;

/**
 * 预制复用匹配参数（issue #108）：最低可用条数与使用概率。
 * <p>
 * 只承载两个数值和它们的固定缺省／范围，不碰世界缓存、网络发送与界面状态；
 * 构造时<b>不</b>做静默钳制——非法值必须由调用方拒绝，而不是悄悄变成合法值。
 * 合法性判断同时供客户端提交检查与服务端写入检查复用（双端同一套规则）。
 */
public final class PrebuiltReuseParameters {

    /** 最低可用条数缺省（服务器缺省与玩家个人缺省一致） */
    public static final int DEFAULT_MIN_ELIGIBLE_ENTRIES = 8;
    /** 使用概率缺省（服务器缺省与玩家个人缺省一致） */
    public static final int DEFAULT_HIT_CHANCE_PERCENT = 60;

    /** 最低可用条数下限 */
    public static final int MIN_ELIGIBLE_MIN = 1;
    /** 最低可用条数上限 */
    public static final int MIN_ELIGIBLE_MAX = 100000;
    /** 概率下限（0 = 从不复用） */
    public static final int HIT_CHANCE_MIN = 0;
    /** 概率上限（100 = 候选充足时必定复用） */
    public static final int HIT_CHANCE_MAX = 100;

    private final int minEligibleEntries;
    private final int hitChancePercent;

    public PrebuiltReuseParameters(int minEligibleEntries, int hitChancePercent) {
        this.minEligibleEntries = minEligibleEntries;
        this.hitChancePercent = hitChancePercent;
    }

    /** 固定缺省参数：8 条 / 60% */
    public static PrebuiltReuseParameters defaults() {
        return new PrebuiltReuseParameters(DEFAULT_MIN_ELIGIBLE_ENTRIES, DEFAULT_HIT_CHANCE_PERCENT);
    }

    /** 最低可用条数合法区间 1～100000 */
    public static boolean isValidMinEligibleEntries(int value) {
        return value >= MIN_ELIGIBLE_MIN && value <= MIN_ELIGIBLE_MAX;
    }

    /** 概率合法区间 0～100 */
    public static boolean isValidHitChancePercent(int value) {
        return value >= HIT_CHANCE_MIN && value <= HIT_CHANCE_MAX;
    }

    public int minEligibleEntries() {
        return minEligibleEntries;
    }

    public int hitChancePercent() {
        return hitChancePercent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PrebuiltReuseParameters other)) {
            return false;
        }
        return minEligibleEntries == other.minEligibleEntries
                && hitChancePercent == other.hitChancePercent;
    }

    @Override
    public int hashCode() {
        return minEligibleEntries * 31 + hitChancePercent;
    }

    @Override
    public String toString() {
        return "PrebuiltReuseParameters[" + minEligibleEntries + ", " + hitChancePercent + "%]";
    }
}
