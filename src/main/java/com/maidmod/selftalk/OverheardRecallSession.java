package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次自动请求（自话／欢迎语／互聊）的偷听资料会话：准备期冻结、渲染块、检索库、正式提交与释放。
 * <p>
 * 资料口径按请求创建时冻结的历史模式固定：
 * <ul>
 *   <li>FULL：本次近期增量（若有）＋ 该女仆所有尚未摘要退出的有效原文；</li>
 *   <li>RETRIEVAL：本次近期增量（若有）＋ 联合候选池实际命中的自身档案分组；</li>
 *   <li>COMPACT：只带本次近期增量，不自动携带旧原文。</li>
 * </ul>
 * 准备、规划与取消都不写记忆；只有正式派发成功后才提交实际带入的编号。
 * 快照只持有编号与 pin，正文按编号从 store 读取；清空／编号重置后的旧快照提交无效。
 */
public final class OverheardRecallSession {

    private final EntityMaid maid;
    private final OverheardChatStore store;
    private final HistoryContextMode mode;
    private final boolean chinese;
    /** 准备时冻结的有效原文编号（用于判定「本次是否有实际增量」） */
    private final Set<Long> effectiveAtPrepare;
    private final OverheardChatStore.Snapshot recent;
    private final List<OverheardChatStore.Group> archivedLibrary;
    /** 联合检索命中的档案分组（RETRIEVAL 模式可能为 null） */
    private OverheardChatStore.Snapshot archived;
    /** 渲染后实际进入请求的主动召回编号（近期 + 命中档案） */
    private final Set<Long> injected = new LinkedHashSet<>();
    /** 其中属于近期窗口的编号（链内复用的「本轮资料」只记这一批） */
    private final Set<Long> recentInjected = new LinkedHashSet<>();

    private OverheardRecallSession(EntityMaid maid, OverheardChatStore store, HistoryContextMode mode,
                                   boolean chinese, Set<Long> effectiveAtPrepare,
                                   OverheardChatStore.Snapshot recent,
                                   List<OverheardChatStore.Group> archivedLibrary) {
        this.maid = maid;
        this.store = store;
        this.mode = mode;
        this.chinese = chinese;
        this.effectiveAtPrepare = effectiveAtPrepare;
        this.recent = recent;
        this.archivedLibrary = archivedLibrary;
    }

    /**
     * 在会话登记准备期之后调用：按一次概率判定与历史模式冻结资料。
     * <p>
     * 与 store 绑定的资料快照只建立短命 pin，不写有效原文或档案。
     *
     * @param recentHit 本次是否命中近期召回概率（欢迎语恒为 false，且不参与判定）
     * @param mode      本次会话冻结的历史模式
     */
    public static OverheardRecallSession prepare(EntityMaid maid, boolean recentHit, HistoryContextMode mode) {
        OverheardChatStore store = storeOf(maid);
        if (store == null) {
            return new OverheardRecallSession(maid, null, mode, false, Set.of(), null, List.of());
        }
        boolean chinese = isChinese(maid);
        Set<Long> effective = effectiveIds(store, maid);
        OverheardChatStore.Snapshot recent = recentHit ? store.prepareRecentRecall(maid.getUUID()) : null;
        // 只有检索模式需要旧资料候选池；其他模式的旧资料目标为空成品
        List<OverheardChatStore.Group> library = mode == HistoryContextMode.RETRIEVAL
                ? store.prepareArchivedLibrary(maid.getUUID()) : List.of();
        return new OverheardRecallSession(maid, store, mode, chinese, effective, recent, library);
    }

    /** 不参与偷听资料的会话（欢迎语、预制复用命中或未启用） */
    public static OverheardRecallSession inactive(EntityMaid maid, HistoryContextMode mode) {
        return new OverheardRecallSession(maid, null, mode, false, Set.of(), null, List.of());
    }

    /**
     * 同链后续轮次的本轮资料复用：只持有编号，不复制正文、不持有长期 pin。
     * <p>
     * 渲染时按当前有效绑定重新读取；已不存在的编号自然被丢弃，不会把旧轮资料凭空带回。
     */
    public static OverheardRecallSession reuse(EntityMaid maid, HistoryContextMode mode, List<Long> ids) {
        OverheardChatStore store = storeOf(maid);
        if (store == null || ids == null || ids.isEmpty()) {
            return inactive(maid, mode);
        }
        OverheardRecallSession session = new OverheardRecallSession(maid, store, mode, isChinese(maid),
                effectiveIds(store, maid), null, List.of());
        session.reusedIds.addAll(ids);
        return session;
    }

    /** 同链复用的本轮资料编号（由首次正式派发时记录） */
    private final List<Long> reusedIds = new ArrayList<>();

    private static OverheardChatStore storeOf(EntityMaid maid) {
        if (maid == null || maid.level().getServer() == null) {
            return null;
        }
        return OverheardChatSavedData.storeOf(maid.level().getServer());
    }

    private static Set<Long> effectiveIds(OverheardChatStore store, EntityMaid maid) {
        Set<Long> ids = new LinkedHashSet<>();
        for (OverheardChatStore.Entry entry : store.readEffectiveMessages(maid.getUUID())) {
            ids.add(entry.id());
        }
        return ids;
    }

    /** 语言判定与请求渲染同一口径：优先女仆聊天语言，空则用配置默认 */
    private static boolean isChinese(EntityMaid maid) {
        String language = maid.getAiChatManager() == null ? null : maid.getAiChatManager().chatLanguage;
        if (language == null || language.isBlank()) {
            language = Config.SELF_TALK_LANGUAGE.get();
        }
        return ContextLanguage.of(SelfTalkContexts.sanitizeLanguage(language)).isZh();
    }

    /** 旧资料候选池（仅 RETRIEVAL 模式非空） */
    public List<OverheardChatStore.Group> archivedLibrary() {
        return archivedLibrary;
    }

    /**
     * 联合检索命中档案分组后调用：按当前档案绑定生成召回快照。
     * <p>
     * 传入分组不属于该女仆当前档案时整批忽略，结果为空即本次无旧资料可带。
     */
    public void recallArchived(List<List<Long>> selectedGroups) {
        if (store == null || selectedGroups == null || selectedGroups.isEmpty()) {
            return;
        }
        archived = store.prepareArchivedRecall(maid.getUUID(), selectedGroups);
    }

    /** 与检索候选池同源的档案分组选择结果（供结果回报与链内复用） */
    public Set<Long> injectedIds() {
        return Set.copyOf(injected);
    }

    /**
     * 渲染本次请求的偷听背景块（同一编号只出现一次）。
     * <p>
     * 近期选择完全没有增量时不额外注入；旧资料按模式读取。
     */
    public String block() {
        if (store == null) {
            return "";
        }
        List<OverheardChatStore.RecalledGroup> groups = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        Set<Long> rendered = new LinkedHashSet<>();
        Set<Long> renderedRecent = new LinkedHashSet<>();
        boolean timeGap = false;
        if (recent != null && recent.hasDelta()) {
            timeGap = recent.timeGap();
            for (OverheardChatStore.RecalledGroup group : recent.renderGroups()) {
                OverheardChatStore.RecalledGroup kept = dedupe(group, seen);
                if (kept != null) {
                    groups.add(kept);
                    rendered.addAll(kept.ids());
                    renderedRecent.addAll(kept.ids());
                }
            }
        }
        if (mode == HistoryContextMode.FULL) {
            // 自动携带：读取此刻的有效原文（摘要已退出的编号不会因准备期快照被带回）
            OverheardChatStore.RecalledGroup kept = dedupe(new OverheardChatStore.RecalledGroup(List.of(),
                    store.readEffectiveMessages(maid.getUUID())), seen);
            if (kept != null) {
                groups.add(kept);
            }
        } else if (mode == HistoryContextMode.RETRIEVAL && archived != null) {
            for (OverheardChatStore.RecalledGroup group : archived.recalledGroups()) {
                OverheardChatStore.RecalledGroup kept = dedupe(group, seen);
                if (kept != null) {
                    groups.add(kept);
                    rendered.addAll(kept.ids());
                }
            }
        }
        // 同链复用：只按当前有效绑定重新读取本轮资料，不复制正文；已退出有效的编号不再带回
        if (!reusedIds.isEmpty()) {
            Set<Long> stillEffective = effectiveIds(store, maid);
            List<Long> usable = new ArrayList<>(reusedIds.size());
            for (Long id : reusedIds) {
                if (stillEffective.contains(id)) {
                    usable.add(id);
                }
            }
            OverheardChatStore.RecalledGroup kept = usable.isEmpty() ? null : dedupe(
                    new OverheardChatStore.RecalledGroup(List.copyOf(usable), store.entriesFor(usable)), seen);
            if (kept != null) {
                groups.add(kept);
            }
        }
        // 实际进入请求的主动召回编号：只算真正渲染出来的近期与档案条目，复用与自动携带不计
        injected.clear();
        injected.addAll(rendered);
        recentInjected.clear();
        recentInjected.addAll(renderedRecent);
        return OverheardChatContext.renderBlock(groups, timeGap, maid.getOwnerUUID(), chinese);
    }

    /** 按已见编号去重一组资料；全部重复或为空时返回 null */
    private static OverheardChatStore.RecalledGroup dedupe(OverheardChatStore.RecalledGroup group, Set<Long> seen) {
        List<OverheardChatStore.Entry> kept = new ArrayList<>();
        List<Long> keptIds = new ArrayList<>();
        for (OverheardChatStore.Entry entry : group.entries()) {
            if (seen.add(entry.id())) {
                kept.add(entry);
                keptIds.add(entry.id());
            }
        }
        return kept.isEmpty() ? null : new OverheardChatStore.RecalledGroup(List.copyOf(keptIds), List.copyOf(kept));
    }

    /** 本次实际渲染进请求的近期窗口编号（首次派发时供同链后续轮次复用） */
    public List<Long> renderedRecentIds() {
        return List.copyOf(recentInjected);
    }

    /**
     * 本次是否算一次实际偷听注入事件：主动召回且实际进入请求的编号，相对准备时有效集合确有增量。
     * 只看编号集合，不扫描 XML、摘要或旧历史。
     */
    public boolean hasEvent() {
        for (Long id : injected) {
            if (!effectiveAtPrepare.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 正式派发成功后提交实际带入的编号（只提交主动召回的近期窗口与命中档案分组）。
     * <p>
     * 自动携带的有效原文与链内复用不重新激活已摘要退出的编号。
     */
    public void commit() {
        if (store == null) {
            return;
        }
        if (recent != null && recent.hasDelta()) {
            store.commitRecall(recent, injected);
        }
        if (archived != null) {
            for (OverheardChatStore.RecalledGroup group : archived.recalledGroups()) {
                store.commitRecall(archived, group.ids());
            }
        }
        OverheardChatSavedData data = OverheardChatSavedData.get(maid.level().getServer());
        if (data != null) {
            data.markDirtyIfChanged();
        }
    }

    /** 幂等释放全部短命快照（取消、失败与提交后都调用） */
    public void close() {
        if (store == null) {
            return;
        }
        if (recent != null) {
            store.closeSnapshot(recent);
        }
        if (archived != null) {
            store.closeSnapshot(archived);
        }
    }
}
