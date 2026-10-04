package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.history.DialogueBlock;
import com.maidmod.selftalk.history.HistoryMessage;
import com.maidmod.selftalk.history.HistoryRetrievalIndex;
import com.maidmod.selftalk.history.PlayerDialogueLibrary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 每只女仆一份的玩家对话检索索引缓存（懒更新，不落盘）。
 * <p>
 * 与实体实例、女仆 UUID、当前主人 UUID 三重绑定：实体 ID 会被复用，
 * 只按实体 ID 记缓存会把上一只女仆的索引交给新实体。
 * <p>
 * 工作方式：
 * <ol>
 *   <li>首次实际需要检索时构建；</li>
 *   <li>玩家发言、回复完成、历史压缩／清空等事件只做失效标记，<b>不</b>立即重建；</li>
 *   <li>下次检索时在服务端主线程取当前来源快照并完成来源过滤；</li>
 *   <li>与上次<b>可检索消息序列</b>比较：相同则复用分块与统计，不同则重建；
 *       每次真正检索前都做一次序列校验，因此 TLM 队列容量淘汰与其他删除路径同样被发现，
 *       不依赖消息数量或更新钩子是否可靠。</li>
 * </ol>
 * 普通自话新增会让原始历史变化，但只要没有挤掉玩家聊天，可检索序列不变，就不重建。
 * <p>
 * 线程划分（见计划 §5.5「来源快照和索引计算分离」）：
 * <ul>
 *   <li>服务端主线程：读实体、附件、来源指纹，算出可检索序列与下标映射（这一步必须主线程）；
 *       这些 {@link HistoryMessage} 是分离的纯文本视图，不持有 {@code LLMMessage} 引用；</li>
 *   <li>单线程文本执行器：只对上面那份不可变文本做分块、分词与 BM25 统计；</li>
 *   <li>构建完成回到主线程：核验该条目的世代号，过期构建不覆盖新缓存（清空／卸载后的结果一律丢弃）。</li>
 * </ul>
 * 执行器全服共用一个线程（不为每只女仆建线程），随服务器停止释放。
 * 缓存只在真正使用检索模式时建立，不为从未使用过的女仆预建索引。
 */
public final class HistoryRetrievalCache {

    /** 女仆 UUID -> 缓存条目（仅服务端主线程访问） */
    private static final Map<UUID, Entry> CACHE = new HashMap<>();
    /** 全服共用的单线程文本工作执行器（懒创建，服务器停止时释放） */
    private static ExecutorService textWorker;

    private HistoryRetrievalCache() {
    }

    /** 缓存条目：绑定实体实例与主人 UUID，并保存上次的来源序列与派生结果 */
    private static final class Entry {
        final EntityMaid maid;
        final UUID ownerUuid;
        /** 世代号：失效／释放即自增，在途构建按它核验是否过期 */
        int generation;
        /** 上次构建时的可检索序列标识（内容指纹列表），用于「序列是否变化」的比较 */
        List<String> sourceTokens;
        /** 分块结果（下标基于可检索序列） */
        List<DialogueBlock> blocks;
        HistoryRetrievalIndex index;
        /** 失效标记：原始历史可能已变化；下次检索先比对来源序列，未变则直接复用并解除标记 */
        boolean dirty;

        Entry(EntityMaid maid, UUID ownerUuid) {
            this.maid = maid;
            this.ownerUuid = ownerUuid;
        }
    }

    /**
     * 一次检索所需的全部材料。
     *
     * @param index      索引（已确保对应当前来源序列）
     * @param searchable 可检索序列及回原始历史区的下标映射
     */
    public record Result(HistoryRetrievalIndex index, SelfTalkHistoryAssembler.SearchableIndices searchable) {

        /** 是否已有可检索的玩家对话（没有就不必发关键词规划请求） */
        public boolean hasSearchableHistory() {
            return index.blockCount() > 0;
        }
    }

    /**
     * 取得当前女仆的检索索引，必要时重建；结果一律经服务端主线程回调。
     * <p>
     * 来源序列未变时同步回调（零额外延迟）；需要重建时把纯文本计算交给文本执行器，
     * 完成后再回主线程回调。返回的 {@code SearchableIndices} 与索引同源：
     * 两者基于同一次主线程快照，因此调用方把召回块映射回历史区时下标不会错位。
     * <p>
     * 构建失败（极少见）时以「没有可检索历史」回调：本次不召回，仍进入一次正式生成。
     */
    public static void indexFor(EntityMaid maid, List<LLMMessage> history, Consumer<Result> onReady) {
        UUID ownerUuid = maid.getOwnerUUID();
        List<HistoryMessage> view = SelfTalkHistoryAssembler.toSearchableView(history);
        SelfTalkHistoryAssembler.SearchableIndices searchable =
                SelfTalkHistoryAssembler.filterSearchable(view, SelfTalkHistoryAssembler.selfTalkFingerprints(maid));

        Entry entry = CACHE.get(maid.getUUID());
        if (entry == null || entry.maid != maid || !Objects.equals(entry.ownerUuid, ownerUuid)) {
            // 实体实例或主人变了（实体 ID 复用、女仆易主）：旧缓存一律作废，不能沿用
            entry = new Entry(maid, ownerUuid);
            CACHE.put(maid.getUUID(), entry);
        }

        List<String> tokens = PlayerDialogueLibrary.snapshotTokens(searchable.searchable());
        if (entry.index != null && entry.sourceTokens != null && entry.sourceTokens.equals(tokens)) {
            // 来源序列未变：直接复用旧索引，失效标记就此解除（序列校验替代了立即重建）
            entry.dirty = false;
            onReady.accept(new Result(entry.index, searchable));
            return;
        }

        final Entry target = entry;
        final int generation = target.generation;
        final List<HistoryMessage> source = searchable.searchable();
        try {
            worker().execute(() -> {
                List<DialogueBlock> blocks = PlayerDialogueLibrary.buildBlocks(source);
                HistoryRetrievalIndex index = HistoryRetrievalIndex.build(blocks);
                // 缓存写回与 onReady 回调都必须在服务端主线程执行：
                // 回调会读取实体与状态机并提交 LLM 请求，不能留在文本工作线程
                publish(target, generation, tokens, blocks, index, new Result(index, searchable), onReady);
            });
        } catch (Throwable t) {
            // 执行器已释放（服务器正在停止）：本次不召回，不复活缓存
            MaidSelfTalkMod.LOGGER.warn("Retrieval index worker unavailable for maid {}, no recall",
                    maid.getId(), t);
            onReady.accept(new Result(HistoryRetrievalIndex.empty(), searchable));
        }
    }

    /**
     * 构建完成：回到服务端主线程后核验世代号写回缓存，并在同一主线程任务里交付回调。
     * <p>
     * 即使世代号已过期（缓存不采用本次构建），索引仍对应本次来源快照，回调照常交付；
     * 服务器已停止时整体放弃——不写缓存也不再回调，调用方的会话随世界一并销毁。
     */
    private static void publish(Entry entry, int generation, List<String> tokens,
                                List<DialogueBlock> blocks, HistoryRetrievalIndex index,
                                Result result, Consumer<Result> onReady) {
        EntityMaid maid = entry.maid;
        if (maid.level().getServer() == null) {
            return;
        }
        maid.level().getServer().execute(() -> {
            if (entry.generation == generation) {
                entry.blocks = blocks;
                entry.index = index;
                entry.sourceTokens = tokens;
                entry.dirty = false;
            }
            onReady.accept(result);
        });
    }

    /** 全服共用的单线程文本工作线程（守护线程：进程退出不需要额外收尾） */
    private static ExecutorService worker() {
        ExecutorService current = textWorker;
        if (current == null || current.isShutdown()) {
            if (current != null && current.isShutdown()) {
                // 上一轮服务器已停止：重建执行器，供下一轮服务器继续使用
                textWorker = null;
            }
            textWorker = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tlm-self-talk-retrieval");
                thread.setDaemon(true);
                return thread;
            });
            return textWorker;
        }
        return current;
    }

    /**
     * 标记该女仆的原始历史可能已变化（玩家发言、回复完成、压缩、工具过程清理后调用）。
     * <p>
     * 只做失效标记而不重建：旧索引与来源序列<b>保留</b>，下一次真正检索时先比对来源序列——
     * 未变则直接复用并解除标记（「只新增了被排除的消息」这类变化不会造成无谓的重新分词），
     * 变了才重建。同时自增世代号：在途的旧构建回来时不再被采用。
     */
    public static void invalidate(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        Entry entry = CACHE.get(maid.getUUID());
        if (entry != null && entry.maid == maid) {
            entry.generation++;
            entry.dirty = true;
        }
    }

    /** 立即丢弃该女仆的缓存（清空记忆、女仆卸载／死亡时调用） */
    public static void drop(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        Entry entry = CACHE.get(maid.getUUID());
        if (entry == null || entry.maid == maid) {
            CACHE.remove(maid.getUUID());
        }
    }

    /** 按女仆 UUID 丢弃（只有 ID 可见的清理路径，如周期性清扫） */
    public static void dropByUuid(UUID maidUuid) {
        if (maidUuid != null) {
            CACHE.remove(maidUuid);
        }
    }

    /**
     * 服务器停止时整体释放：清空缓存并关闭文本执行器。
     * <p>
     * 不落盘——进程内存而已，显式清掉便于整合包在同一 JVM 内反复重载服务器。
     * 在途构建随之取消；执行器在下次真正需要检索时按需重建。
     */
    public static void dropAll() {
        CACHE.clear();
        ExecutorService current = textWorker;
        textWorker = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    /** 当前缓存条目数（自检与诊断用） */
    static int size() {
        return CACHE.size();
    }
}
