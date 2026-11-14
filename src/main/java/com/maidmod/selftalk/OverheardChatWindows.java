package com.maidmod.selftalk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 附近玩家聊天近期窗口的纯编号计算（不依赖 Minecraft / TLM 类型，可脱离游戏自检）。
 * <p>
 * 固定口径（不扩展成可变数量策略）：
 * <ul>
 *   <li>单锚点 {@code a}：{@code [a-4, a+4]}；</li>
 *   <li>两个锚点 {@code a < b}：{@code b-a <= 14} 时合并成一个连接区间 {@code [a-4, b+4]}；</li>
 *   <li>相距更远时分别输出两个基础区间，中间插入时间间隔提示；外侧不额外扩展。</li>
 * </ul>
 * 所有区间裁剪到合法起始编号与当前已分配末尾，按编号排序去重；尚未到达的编号不生成占位。
 * 例：第 50 条时锚点 16、30 → {@code 12～34}；第 29 条时 1、16 → {@code 1～5} + 间隔 + {@code 12～20}。
 */
public final class OverheardChatWindows {

    /** 基础窗口半径：锚点前后各 4 条 */
    public static final int BASE_RADIUS = 4;
    /** 动态窗口半径：锚点前后各 7 条，用于两个动态窗口重合时的相向扩展 */
    public static final int DYNAMIC_RADIUS = 7;
    /** 两锚点合并上限：差值不大于该值时动态窗口相接 */
    public static final int CONNECT_GAP = DYNAMIC_RADIUS * 2;

    private OverheardChatWindows() {
    }

    /** 闭区间窗口（含首尾编号） */
    public record Window(long startId, long endId) {

        public boolean isEmpty() {
            return endId < startId;
        }
    }

    /** 选中结果：按编号从旧到新的窗口序列，以及窗口之间是否需要时间间隔提示 */
    public record Selection(List<Window> windows, boolean timeGap) {
    }

    /**
     * 按最新两个锚点计算窗口。
     *
     * @param anchors 该女仆保存的锚点（最多取最新的两个，顺序不限）
     * @param firstId 合法起始编号
     * @param lastId  当前已分配末尾编号；小于 {@code firstId} 表示尚无消息
     */
    public static Selection select(List<Long> anchors, long firstId, long lastId) {
        if (lastId < firstId || anchors == null || anchors.isEmpty()) {
            return new Selection(List.of(), false);
        }
        List<Long> valid = new ArrayList<>(2);
        for (long anchor : anchors) {
            if (anchor >= firstId && anchor <= lastId && !valid.contains(anchor)) {
                valid.add(anchor);
            }
        }
        if (valid.isEmpty()) {
            return new Selection(List.of(), false);
        }
        valid.sort(Long::compare);
        if (valid.size() == 1) {
            long only = valid.get(0);
            return new Selection(List.of(clip(new Window(only - BASE_RADIUS, only + BASE_RADIUS),
                    firstId, lastId)), false);
        }
        long a = valid.get(valid.size() - 2);
        long b = valid.get(valid.size() - 1);
        if (b - a <= CONNECT_GAP) {
            return new Selection(List.of(clip(new Window(a - BASE_RADIUS, b + BASE_RADIUS), firstId, lastId)),
                    false);
        }
        List<Window> windows = new ArrayList<>(2);
        addIfNotEmpty(windows, clip(new Window(a - BASE_RADIUS, a + BASE_RADIUS), firstId, lastId));
        addIfNotEmpty(windows, clip(new Window(b - BASE_RADIUS, b + BASE_RADIUS), firstId, lastId));
        return new Selection(List.copyOf(windows), windows.size() > 1);
    }

    /** 选区内的全部编号（有序去重） */
    public static List<Long> ids(Selection selection) {
        if (selection == null || selection.windows().isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Window window : selection.windows()) {
            for (long id = window.startId(); id <= window.endId(); id++) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /** 最新锚点的前向候选：{@code [anchor+1, anchor+7]}，裁剪到已分配末尾；未分配部分不生成占位 */
    public static Window candidateWindow(long anchor, long lastId) {
        return new Window(anchor + 1, Math.min(anchor + DYNAMIC_RADIUS, lastId));
    }

    private static Window clip(Window window, long firstId, long lastId) {
        return new Window(Math.max(window.startId(), firstId), Math.min(window.endId(), lastId));
    }

    private static void addIfNotEmpty(List<Window> windows, Window window) {
        if (!window.isEmpty()) {
            windows.add(window);
        }
    }
}
