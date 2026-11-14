package com.maidmod.selftalk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 附近玩家聊天偷听的集中存档逻辑（纯 Java，不依赖 Minecraft／TLM）。
 * <p>
 * 三份数据严格分离，正文只存一份：
 * <ul>
 *   <li>{@link Message}：全服合格的玩家聊天正文表，按编号有序；编号只在服务端主线程分配；</li>
 *   <li>{@link MaidRef}：每只女仆的引用记录（最新两个锚点、有效原文、已召回档案分组、后文补齐进度）；</li>
 *   <li>{@link Snapshot}：一次请求的短命资料快照（pin 住期间要用的正文，不写有效／档案）。</li>
 * </ul>
 * 回收只看三件事：其他女仆／快照是否仍引用、是否仍在最新 7 条缓冲中；
 * 两者都没有才删除正文。只在引用或缓冲变化时处理受影响编号，不设定时全库扫描。
 * <p>
 * 编号达到上限后，下一次分配会清空本功能的正文、引用、缓冲、补齐与快照，再从下限重新开始；
 * 不清空其他聊天、人设或设置。清空／重置会推进数据世代，旧快照的提交不能写进新数据。
 */
public final class OverheardChatStore {

    /** 编号下限：初值即第一条之前的「已分配末尾」，第一条使用下限加一 */
    public static final long ID_START = 100_000_000_000_000_000L;
    /** 合法起始编号（第一条） */
    public static final long ID_FIRST = ID_START + 1;
    /** 编号上限：该值可正常分配，下一次分配时整体重置 */
    public static final long ID_CAP = 999_999_999_999_999_999L;
    /** 全服合格序列的最新缓冲长度（只存编号） */
    public static final int RECENT_BUFFER_SIZE = 7;
    /** 每只女仆保留的最新不同锚点数 */
    public static final int MAX_ANCHORS = 2;

    /** 消息来源：普通聊天或玩家本人执行的 {@code /say} */
    public enum Source {NORMAL_CHAT, PLAYER_SAY}

    /** 集中正文表条目（持久） */
    public record Message(long id, UUID playerUuid, String playerName, long epochMillis, String body, Source source) {
    }

    /** 请求资料里的一行聊天（快照用，不可变） */
    public record Entry(long id, UUID playerUuid, String playerName, long epochMillis, String body, Source source) {

        static Entry of(Message message) {
            return new Entry(message.id(), message.playerUuid(), message.playerName(),
                    message.epochMillis(), message.body(), message.source());
        }
    }

    /** 已召回档案的一组原文（持久只存编号；{@code text} 是检索用纯文本，运行时生成） */
    public record Group(List<Long> ids, String text) {
    }

    /** 渲染用的一组原文 */
    public record RecalledGroup(List<Long> ids, List<Entry> entries) {
    }

    /** 每只女仆的引用记录（持久字段 + 对象身份） */
    public static final class MaidRef {
        /** 最新两个不同锚点，从旧到新；锚点是召回起点，不是正文条数上限 */
        final List<Long> anchors = new ArrayList<>(MAX_ANCHORS);
        /** 尚未被摘要覆盖的有效原文编号 */
        final Set<Long> effective = new LinkedHashSet<>();
        /** 已召回档案：有序编号分组，保留一次资料片段的边界 */
        final List<List<Long>> archive = new ArrayList<>();
        /** 最新锚点待补齐的下一编号；-1 表示没有等待 */
        long fillNextId = -1;
    }

    /** 存档导出／恢复用的女仆引用记录 */
    public record MaidState(List<Long> anchors, List<Long> effective, List<List<Long>> archive, long fillNextId) {
    }

    /** 存档导出／恢复用的整体状态 */
    public record State(long lastAssignedId, List<Message> messages, List<Long> recentBuffer,
                        Map<UUID, MaidState> maids) {
    }

    /**
     * 一次请求的短命资料快照。
     * <p>
     * 绑定创建它的 store、女仆引用记录对象身份与数据世代：清空／重置后这些绑定失效，
     * 旧快照不能凭相同编号在新数据里重新找到正文。
     */
    public static final class Snapshot {
        private final OverheardChatStore store;
        private final int generation;
        private final UUID maidUuid;
        private final MaidRef owner;
        private final List<Entry> recentEntries;
        private final boolean timeGap;
        private final List<RecalledGroup> recalledGroups;
        private final List<Long> materialIds;
        private final Set<Long> deltaIds;

        private Snapshot(OverheardChatStore store, int generation, UUID maidUuid, MaidRef owner,
                         List<Entry> recentEntries, boolean timeGap, List<RecalledGroup> recalledGroups,
                         List<Long> materialIds, Set<Long> deltaIds) {
            this.store = store;
            this.generation = generation;
            this.maidUuid = maidUuid;
            this.owner = owner;
            this.recentEntries = recentEntries;
            this.timeGap = timeGap;
            this.recalledGroups = recalledGroups;
            this.materialIds = materialIds;
            this.deltaIds = deltaIds;
        }

        /** 近期窗口内的聊天（从旧到新）；档案快照为空 */
        public List<Entry> recentEntries() {
            return recentEntries;
        }

        /** 两个窗口之间是否需要时间间隔提示 */
        public boolean timeGap() {
            return timeGap;
        }

        /** 本快照实际召回的档案分组（从旧到新）；近期快照为空 */
        public List<RecalledGroup> recalledGroups() {
            return recalledGroups;
        }

        /** 本快照涉及的全部编号（近期窗口 + 档案分组） */
        public List<Long> materialIds() {
            return materialIds;
        }

        /** 相对准备时有效集合的增量编号（本次实际新增／重新召回的候选） */
        public Set<Long> deltaIds() {
            return deltaIds;
        }

        /** 是否有实际增量（无增量时近期快照不额外注入，也不算一次偷听注入事件） */
        public boolean hasDelta() {
            return !deltaIds.isEmpty();
        }

        /** 按编号合并本快照要渲染的分组（近期窗口在前，档案分组在后） */
        public List<RecalledGroup> renderGroups() {
            List<RecalledGroup> groups = new ArrayList<>();
            if (!recentEntries.isEmpty()) {
                List<Long> ids = new ArrayList<>(recentEntries.size());
                for (Entry entry : recentEntries) {
                    ids.add(entry.id());
                }
                groups.add(new RecalledGroup(List.copyOf(ids), recentEntries));
            }
            groups.addAll(recalledGroups);
            return List.copyOf(groups);
        }

        private boolean valid() {
            return store.generation == generation && store.maids.get(maidUuid) == owner;
        }
    }

    // ===== 运行态 =====

    private long lastAssignedId = ID_START;
    /** 编号 -> 正文（按编号天然有序） */
    private final Map<Long, Message> messages = new LinkedHashMap<>();
    /** 全服合格序列最新 7 个编号（从旧到新） */
    private final Deque<Long> recentBuffer = new ArrayDeque<>();
    /** 女仆 UUID -> 引用记录 */
    private final Map<UUID, MaidRef> maids = new LinkedHashMap<>();
    /** 编号 -> 引用它的女仆 UUID 集合（由持久字段重建，不落盘） */
    private final Map<Long, Set<UUID>> references = new HashMap<>();
    /** 女仆 UUID -> 该女仆当前引用的全部编号（增量维护用） */
    private final Map<UUID, Set<Long>> maidMembership = new HashMap<>();
    /** 仍需补齐后文的等待项（不落盘，由 fillNextId 重建） */
    private final Set<UUID> waiting = new LinkedHashSet<>();
    /** 编号 -> 短命快照 pin 计数 */
    private final Map<Long, Integer> pins = new HashMap<>();
    /** 未关闭的快照（按对象身份） */
    private final Set<Snapshot> openSnapshots = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 数据世代：清空单只／编号重置时推进，令旧快照失效 */
    private int generation;
    /** 停服后关闭快照与提交能力（持久字段保留） */
    private boolean closed;

    public OverheardChatStore() {
    }

    // ===== 采集 =====

    /**
     * 每条合格聊天无条件调用一次：分配编号并处理听见集合。
     * <p>
     * 集合为空也记录进全服短缓冲并推进既有等待项——编号递增与「是否有女仆听见」无关。
     * 关闭运行态后不再写入，返回 -1。
     *
     * @return 本次分配的世界级编号；不可写入时为 -1
     */
    public long recordQualified(UUID playerUuid, String playerName, long epochMillis, String body,
                                Source source, Collection<UUID> heardMaidUuids) {
        if (closed) {
            return -1;
        }
        long id = allocate();
        messages.put(id, new Message(id, playerUuid, playerName, epochMillis, body, source));
        recentBuffer.addLast(id);
        Long evicted = null;
        while (recentBuffer.size() > RECENT_BUFFER_SIZE) {
            evicted = recentBuffer.removeFirst();
        }
        // 先推进所有仍在等待后文的已登记女仆：每条新消息只推进这些等待项
        for (UUID maidUuid : new ArrayList<>(waiting)) {
            MaidRef ref = maids.get(maidUuid);
            if (ref == null) {
                waiting.remove(maidUuid);
                continue;
            }
            advanceFill(ref);
            if (ref.fillNextId <= 0) {
                waiting.remove(maidUuid);
            }
        }
        Set<UUID> heard = heardMaidUuids == null ? Set.of() : new LinkedHashSet<>(heardMaidUuids);
        for (UUID maidUuid : heard) {
            if (maidUuid == null) {
                continue;
            }
            MaidRef ref = maids.computeIfAbsent(maidUuid, key -> new MaidRef());
            advanceFill(ref);
            if (ref.anchors.isEmpty() || ref.anchors.get(ref.anchors.size() - 1) != id) {
                ref.anchors.add(id);
                while (ref.anchors.size() > MAX_ANCHORS) {
                    ref.anchors.remove(0);
                }
            }
            // 最新锚点的后文补齐只登记进度，不直接新增有效原文或档案
            ref.fillNextId = id + 1;
            if (ref.fillNextId > id + OverheardChatWindows.DYNAMIC_RADIUS) {
                ref.fillNextId = -1;
            } else {
                waiting.add(maidUuid);
            }
            refreshReferences(maidUuid);
        }
        releaseIfUnused(evicted);
        return id;
    }

    /** 推进等待项的补齐进度；后 7 条全部到齐后仅取消等待（候选正文由锚点引用继续保留） */
    private void advanceFill(MaidRef ref) {
        if (ref.fillNextId <= 0) {
            return;
        }
        long newest = ref.anchors.isEmpty() ? -1 : ref.anchors.get(ref.anchors.size() - 1);
        if (newest <= 0) {
            ref.fillNextId = -1;
            return;
        }
        long limit = newest + OverheardChatWindows.DYNAMIC_RADIUS;
        if (lastAssignedId >= limit) {
            ref.fillNextId = -1;
        } else {
            ref.fillNextId = lastAssignedId + 1;
        }
    }

    /** 编号分配：到上限后整体重置该功能的数据，再从下限开始 */
    private long allocate() {
        if (lastAssignedId >= ID_CAP) {
            reset();
        }
        lastAssignedId++;
        return lastAssignedId;
    }

    /** 编号重置：只清本功能的正文、引用、缓冲、补齐与快照，不动其他聊天／人设／设置 */
    private void reset() {
        messages.clear();
        maids.clear();
        recentBuffer.clear();
        references.clear();
        maidMembership.clear();
        waiting.clear();
        pins.clear();
        openSnapshots.clear();
        lastAssignedId = ID_START;
        generation++;
    }

    // ===== 准备与提交 =====

    /**
     * 冻结该女仆两个锚点的当前可用窗口、正文与相对有效原文的增量。
     * <p>
     * 只建立短命 pin，不写有效／档案；没有锚点或窗口为空时返回 null。
     */
    public Snapshot prepareRecentRecall(UUID maidUuid) {
        MaidRef ref = maids.get(maidUuid);
        if (closed || ref == null || ref.anchors.isEmpty()) {
            return null;
        }
        OverheardChatWindows.Selection selection =
                OverheardChatWindows.select(ref.anchors, ID_FIRST, lastAssignedId);
        List<Long> ids = OverheardChatWindows.ids(selection);
        List<Entry> entries = entriesOf(ids);
        if (entries.isEmpty()) {
            return null;
        }
        Set<Long> material = new LinkedHashSet<>();
        for (Entry entry : entries) {
            material.add(entry.id());
        }
        Set<Long> delta = new LinkedHashSet<>(material);
        delta.removeAll(ref.effective);
        Snapshot snapshot = new Snapshot(this, generation, maidUuid, ref, List.copyOf(entries),
                selection.timeGap(), List.of(), List.copyOf(material), Set.copyOf(delta));
        pin(snapshot);
        return snapshot;
    }

    /**
     * 冻结该女仆已召回片段的编号分组与纯文本，供旧资料检索。
     * <p>
     * 不包含全服其他消息；没有档案时返回空列表。
     */
    public List<Group> prepareArchivedLibrary(UUID maidUuid) {
        MaidRef ref = maids.get(maidUuid);
        if (closed || ref == null || ref.archive.isEmpty()) {
            return List.of();
        }
        List<Group> groups = new ArrayList<>(ref.archive.size());
        for (List<Long> ids : ref.archive) {
            List<Entry> entries = entriesOf(ids);
            if (entries.size() != ids.size()) {
                continue;
            }
            groups.add(new Group(List.copyOf(ids), textOf(entries)));
        }
        return List.copyOf(groups);
    }

    /**
     * 仅接受该女仆档案里已有的 ID 分组，生成检索成品快照与有效原文增量。
     * <p>
     * 传入分组不在档案中（含编号重置后的旧分组）时整批忽略。
     */
    public Snapshot prepareArchivedRecall(UUID maidUuid, Collection<List<Long>> selectedGroups) {
        MaidRef ref = maids.get(maidUuid);
        if (closed || ref == null || selectedGroups == null || selectedGroups.isEmpty()) {
            return null;
        }
        List<RecalledGroup> recalled = new ArrayList<>();
        Set<Long> material = new LinkedHashSet<>();
        for (List<Long> selection : selectedGroups) {
            if (selection == null || selection.isEmpty() || !containsGroup(ref.archive, selection)) {
                continue;
            }
            List<Entry> entries = entriesOf(selection);
            if (entries.size() != selection.size()) {
                continue;
            }
            recalled.add(new RecalledGroup(List.copyOf(selection), List.copyOf(entries)));
            material.addAll(selection);
        }
        if (recalled.isEmpty()) {
            return null;
        }
        Set<Long> delta = new LinkedHashSet<>(material);
        delta.removeAll(ref.effective);
        Snapshot snapshot = new Snapshot(this, generation, maidUuid, ref, List.of(), false,
                List.copyOf(recalled), List.copyOf(material), Set.copyOf(delta));
        pin(snapshot);
        return snapshot;
    }

    /** 只返回尚未被摘要覆盖的有效原文（按编号从旧到新），供全量与正常玩家聊天使用 */
    public List<Entry> readEffectiveMessages(UUID maidUuid) {
        MaidRef ref = maids.get(maidUuid);
        if (ref == null || ref.effective.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(ref.effective);
        ids.sort(Long::compare);
        return entriesOf(ids);
    }

    /** 按编号取仍存在的正文（链内复用 ID 时按当前绑定校验，不复制正文） */
    public List<Entry> entriesFor(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of() : entriesOf(ids);
    }

    /**
     * 正式派发返回成功后调用：仅提交实际带入的编号。
     * <p>
     * 实际资料分组只来自本次已注入编号与该女仆此前档案的并集；尚未注入的新候选不写成已召回记忆。
     *
     * @return 真正新增到有效原文的编号集合（决定本次是否算一次偷听注入事件）
     */
    public Set<Long> commitRecall(Snapshot snapshot, Collection<Long> actuallyInjectedIds) {
        if (snapshot == null || closed || !snapshot.valid() || actuallyInjectedIds == null) {
            return Set.of();
        }
        MaidRef ref = snapshot.owner;
        Set<Long> injected = new HashSet<>(actuallyInjectedIds);
        Set<Long> added = new LinkedHashSet<>();
        for (Long id : snapshot.materialIds) {
            if (injected.contains(id) && messages.containsKey(id) && ref.effective.add(id)) {
                added.add(id);
            }
        }
        for (RecalledGroup group : snapshot.recalledGroups) {
            if (injected.containsAll(group.ids()) && !containsGroup(ref.archive, group.ids())) {
                ref.archive.add(List.copyOf(group.ids()));
            }
        }
        refreshReferences(snapshot.maidUuid);
        return Set.copyOf(added);
    }

    /** 仅移除本次摘要覆盖的有效编号；保留档案、锚点及候选引用 */
    public void retireEffective(UUID maidUuid, Collection<Long> coveredIds) {
        if (closed || coveredIds == null || coveredIds.isEmpty()) {
            return;
        }
        MaidRef ref = maids.get(maidUuid);
        if (ref == null) {
            return;
        }
        ref.effective.removeAll(coveredIds);
        refreshReferences(maidUuid);
    }

    /** 删除这只女仆所有持久偷听引用、补齐与运行态快照；其他女仆的持久记录不受影响 */
    public void clearMaid(UUID maidUuid) {
        if (maidUuid == null) {
            return;
        }
        MaidRef removed = maids.remove(maidUuid);
        waiting.remove(maidUuid);
        Set<Long> previous = maidMembership.remove(maidUuid);
        if (previous != null) {
            for (Long id : previous) {
                Set<UUID> owners = references.get(id);
                if (owners != null) {
                    owners.remove(maidUuid);
                    if (owners.isEmpty()) {
                        references.remove(id);
                        releaseIfUnused(id);
                    }
                }
            }
        }
        if (removed != null) {
            // 绑定到已移除引用记录的旧快照不能继续使用；pin 随引用移除一并释放
            List<Snapshot> stale = new ArrayList<>();
            for (Snapshot snapshot : openSnapshots) {
                if (snapshot.owner == removed) {
                    stale.add(snapshot);
                }
            }
            for (Snapshot snapshot : stale) {
                closeSnapshot(snapshot);
            }
            generation++;
        }
    }

    /** 幂等释放 pin，不自动提交 */
    public void closeSnapshot(Snapshot snapshot) {
        if (snapshot == null || !openSnapshots.remove(snapshot)) {
            return;
        }
        for (Long id : snapshot.materialIds) {
            Integer count = pins.get(id);
            if (count == null || count <= 1) {
                pins.remove(id);
                releaseIfUnused(id);
            } else {
                pins.put(id, count - 1);
            }
        }
    }

    /** 停服后关闭本 store 的快照／提交能力，不删除持久字段 */
    public void closeRuntime() {
        closed = true;
        openSnapshots.clear();
        pins.clear();
        generation++;
    }

    // ===== 存档导入／导出 =====

    /** 导出持久字段（不含运行态 pin 与快照） */
    public State snapshotForSave() {
        Map<UUID, MaidState> maidStates = new LinkedHashMap<>();
        for (Map.Entry<UUID, MaidRef> entry : maids.entrySet()) {
            MaidRef ref = entry.getValue();
            List<List<Long>> archive = new ArrayList<>(ref.archive.size());
            for (List<Long> group : ref.archive) {
                archive.add(List.copyOf(group));
            }
            List<Long> effective = new ArrayList<>(ref.effective);
            effective.sort(Long::compare);
            maidStates.put(entry.getKey(), new MaidState(List.copyOf(ref.anchors),
                    List.copyOf(effective), List.copyOf(archive), ref.fillNextId));
        }
        return new State(lastAssignedId, List.copyOf(messages.values()), List.copyOf(recentBuffer),
                Map.copyOf(maidStates));
    }

    /**
     * 从存档恢复：一次性重建引用与等待索引，回收旧在途快照留下的无主正文。
     * <p>
     * 快照不恢复；缓冲与补齐恢复。恢复后继续接收合格消息补齐。
     */
    public void restore(State state) {
        messages.clear();
        maids.clear();
        recentBuffer.clear();
        references.clear();
        maidMembership.clear();
        waiting.clear();
        pins.clear();
        openSnapshots.clear();
        if (state == null) {
            lastAssignedId = ID_START;
            generation++;
            return;
        }
        lastAssignedId = Math.max(ID_START, Math.min(ID_CAP, state.lastAssignedId()));
        for (Message message : state.messages()) {
            if (message != null && message.id() > ID_START && message.id() <= lastAssignedId) {
                messages.put(message.id(), message);
            }
        }
        for (Long id : state.recentBuffer()) {
            if (id != null && messages.containsKey(id) && !recentBuffer.contains(id)) {
                recentBuffer.addLast(id);
            }
        }
        while (recentBuffer.size() > RECENT_BUFFER_SIZE) {
            recentBuffer.removeFirst();
        }
        for (Map.Entry<UUID, MaidState> entry : state.maids().entrySet()) {
            MaidState saved = entry.getValue();
            if (saved == null) {
                continue;
            }
            MaidRef ref = new MaidRef();
            Set<Long> anchors = new LinkedHashSet<>();
            for (Long anchor : saved.anchors()) {
                if (anchor != null && messages.containsKey(anchor)) {
                    anchors.add(anchor);
                }
            }
            ref.anchors.addAll(anchors);
            ref.anchors.sort(Long::compare);
            while (ref.anchors.size() > MAX_ANCHORS) {
                ref.anchors.remove(0);
            }
            for (Long id : saved.effective()) {
                if (id != null && messages.containsKey(id)) {
                    ref.effective.add(id);
                }
            }
            for (List<Long> group : saved.archive()) {
                if (group == null || group.isEmpty()) {
                    continue;
                }
                boolean intact = true;
                List<Long> copy = new ArrayList<>(group.size());
                for (Long id : group) {
                    if (id == null || !messages.containsKey(id)) {
                        intact = false;
                        break;
                    }
                    copy.add(id);
                }
                if (intact && !containsGroup(ref.archive, copy)) {
                    ref.archive.add(List.copyOf(copy));
                }
            }
            ref.fillNextId = saved.fillNextId();
            maids.put(entry.getKey(), ref);
            if (ref.fillNextId > 0) {
                waiting.add(entry.getKey());
            }
        }
        generation++;
        for (UUID maidUuid : new ArrayList<>(maids.keySet())) {
            refreshReferences(maidUuid);
        }
        // 加载时的一次性回收：旧在途快照不恢复，其正文若无其他引用与缓冲即删除
        for (Long id : new ArrayList<>(messages.keySet())) {
            releaseIfUnused(id);
        }
    }

    // ===== 引用与回收 =====

    /** 该女仆当前引用的全部编号：近期窗口、最新锚点候选、有效原文与档案的并集 */
    private Set<Long> membershipOf(MaidRef ref) {
        Set<Long> ids = new LinkedHashSet<>();
        OverheardChatWindows.Selection selection =
                OverheardChatWindows.select(ref.anchors, ID_FIRST, lastAssignedId);
        for (Long id : OverheardChatWindows.ids(selection)) {
            if (messages.containsKey(id)) {
                ids.add(id);
            }
        }
        if (!ref.anchors.isEmpty()) {
            long newest = ref.anchors.get(ref.anchors.size() - 1);
            for (long id = newest + 1; id <= newest + OverheardChatWindows.DYNAMIC_RADIUS; id++) {
                if (messages.containsKey(id)) {
                    ids.add(id);
                }
            }
        }
        for (Long id : ref.effective) {
            if (messages.containsKey(id)) {
                ids.add(id);
            }
        }
        for (List<Long> group : ref.archive) {
            for (Long id : group) {
                if (messages.containsKey(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private void refreshReferences(UUID maidUuid) {
        MaidRef ref = maids.get(maidUuid);
        if (ref == null) {
            return;
        }
        Set<Long> next = membershipOf(ref);
        Set<Long> previous = maidMembership.getOrDefault(maidUuid, Set.of());
        for (Long id : previous) {
            if (!next.contains(id)) {
                Set<UUID> owners = references.get(id);
                if (owners != null) {
                    owners.remove(maidUuid);
                    if (owners.isEmpty()) {
                        references.remove(id);
                        releaseIfUnused(id);
                    }
                }
            }
        }
        for (Long id : next) {
            if (!previous.contains(id)) {
                references.computeIfAbsent(id, key -> new HashSet<>()).add(maidUuid);
            }
        }
        if (next.isEmpty()) {
            maidMembership.remove(maidUuid);
        } else {
            maidMembership.put(maidUuid, next);
        }
    }

    private void pin(Snapshot snapshot) {
        openSnapshots.add(snapshot);
        for (Long id : snapshot.materialIds) {
            pins.merge(id, 1, Integer::sum);
        }
    }

    /** 无引用且不在缓冲中才删除正文；仍被其他女仆／快照引用则保留 */
    private void releaseIfUnused(Long id) {
        if (id == null) {
            return;
        }
        if (references.containsKey(id) || pins.containsKey(id) || recentBuffer.contains(id)) {
            return;
        }
        messages.remove(id);
    }

    // ===== 查询 =====

    /** 该编号的正文（不存在时为 null） */
    Message messageOf(long id) {
        return messages.get(id);
    }

    /** 当前已分配末尾编号 */
    public long lastAssignedId() {
        return lastAssignedId;
    }

    /** 是否仍有引用记录（自检用） */
    boolean hasMaid(UUID maidUuid) {
        return maids.containsKey(maidUuid);
    }

    /** 现存正文条数（自检用） */
    int messageCount() {
        return messages.size();
    }

    private List<Entry> entriesOf(Collection<Long> ids) {
        List<Entry> entries = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Message message = messages.get(id);
            if (message != null) {
                entries.add(Entry.of(message));
            }
        }
        entries.sort((a, b) -> Long.compare(a.id(), b.id()));
        return List.copyOf(entries);
    }

    /** 档案分组的检索用纯文本：与请求渲染同一行格式，便于关键词命中自然语言内容 */
    private static String textOf(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append('<').append(displayNameOf(entry)).append('>')
                    .append('：').append(entry.body());
        }
        return sb.toString();
    }

    /** 发送时显示名（空名回退为编号，不参与主人身份判断） */
    public static String displayNameOf(Entry entry) {
        return entry.playerName() == null || entry.playerName().isBlank()
                ? "#" + entry.id() : entry.playerName();
    }

    private static boolean containsGroup(List<List<Long>> groups, Collection<Long> ids) {
        for (List<Long> group : groups) {
            if (group.size() == ids.size() && group.containsAll(ids)) {
                return true;
            }
        }
        return false;
    }
}
