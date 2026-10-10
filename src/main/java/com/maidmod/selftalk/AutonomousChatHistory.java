package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.CappedQueue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单只女仆的独立聊天档案与有效自话上下文（自话、欢迎语、互聊）。
 * <p>
 * 与 TLM 的可压缩历史彼此独立，三套数据各自管理：
 * <ol>
 *   <li><b>展示档案</b>：最近配置上限条记录（含已被手动隐藏的记录），自动遗忘不删除，
 *       超限按最旧淘汰；</li>
 *   <li><b>有效自话上下文</b>：仍可参与模型请求的自话／欢迎语，固定最多
 *       {@link #VALID_CONTEXT_LIMIT} 条，单独持久化，<b>不能</b>由展示档案反向生成；</li>
 *   <li><b>运行时窗口</b>：不在这里，由 {@link SelfTalkState} 持有，重启不复活。</li>
 * </ol>
 * <b>红线</b>：展示档案与有效上下文必须是两套生命周期，绝不把「是否仍在档案里」
 * 当作「是否可以进入模型上下文」的依据，反之亦然。
 * <p>
 * 顺序号：本实例内单调递增，与 TLM 历史消息的顺序号取自同一分配器，
 * 因此混合展示可按真实发生顺序合并（各维度 {@code gameTime} 独立计数，不能用于跨维度排序）。
 * <p>
 * 线程约定：分配顺序号、实际入队（{@link #trackTlmEnqueue}）与旁路登记在同一把锁内完成——
 * 玩家 chat 的响应回调在 LLM 响应线程写历史，服务端主线程同时可能在读档／保存，
 * 不锁会次序反转。读档与保存本身在各自线程单次完成，不与其他写并发。
 */
public final class AutonomousChatHistory {

    /** 自定义 NBT 键（与 TLM 自身的 MaidHistory* 键同层） */
    public static final String NBT_KEY = "maid_self_talk:autonomous_history";

    /** 数据版本；读档时版本更高的数据不做猜测性解释，按空处理 */
    private static final int DATA_VERSION = 1;

    /** 有效自话上下文条数上限（固定，不随展示档案配置变化） */
    public static final int VALID_CONTEXT_LIMIT = 512;

    private static final String TAG_VERSION = "Version";
    private static final String TAG_MIGRATION_DONE = "MigrationDone";
    private static final String TAG_NEXT_SEQ = "NextSeq";
    private static final String TAG_HIDDEN_BELOW = "HiddenBelowSeq";
    private static final String TAG_ARCHIVE = "Archive";
    private static final String TAG_VALID_SELF_TALK = "ValidSelfTalk";
    private static final String TAG_TLM_ORDER = "TlmOrder";

    /** 所属女仆（只用于判定客户端／服务端与取值，不反向持有到实体数据） */
    private final EntityMaid maid;

    /** 保护顺序号分配、TLM 入队与旁路登记（响应线程写、主线程读） */
    private final Object lock = new Object();

    /** 展示档案：头部最旧、尾部最新 */
    private final Deque<AutonomousChatRecord> archive = new ArrayDeque<>();
    /** 有效自话上下文：头部最旧、尾部最新，上限 {@link #VALID_CONTEXT_LIMIT} */
    private final Deque<AutonomousChatRecord> validSelfTalk = new ArrayDeque<>();
    /** TLM 历史消息对象 → 顺序号的旁路关联（按对象身份，不按正文相等） */
    private final Map<LLMMessage, Long> orderByMessage = new IdentityHashMap<>();

    private long nextSeq = 1;
    /** 手动清空的展示隐藏边界：顺序号小于该值的独立记录不展示（但仍在档案里占额度） */
    private long hiddenBelowSeq = 0;
    private boolean migrationDone = false;
    /** 已读档标记：避免陈旧存档把内存里更新的数据覆盖回初始状态 */
    private boolean loaded = false;

    public AutonomousChatHistory(EntityMaid maid) {
        this.maid = maid;
    }

    private boolean clientSide() {
        return maid != null && maid.level() != null && maid.level().isClientSide();
    }

    // ===== 顺序号与 TLM 旁路登记 =====

    /**
     * 分配顺序号并完成 TLM 历史入队（四个 {@code add*History} 的唯一入口）。
     * <p>
     * 分配、入队与旁路登记在同一把锁内：若分成两步，玩家回调线程与服务端线程交错时
     * 会出现「顺序号与队内实际次序相反」，混合展示就会错序。
     */
    public void trackTlmEnqueue(CappedQueue<LLMMessage> queue, LLMMessage message) {
        if (message == null) {
            return;
        }
        synchronized (lock) {
            orderByMessage.put(message, nextSeq++);
            if (queue != null) {
                queue.add(message);
            }
        }
    }

    /** TLM 消息的顺序号；未登记返回 null（旧存档未补号的消息由读档／保存路径补号） */
    public Long seqOf(LLMMessage message) {
        synchronized (lock) {
            return orderByMessage.get(message);
        }
    }

    /**
     * 清掉已不在 TLM 队列中的死条目（容量淘汰、压缩、清空都不会回调本类）。
     * 只清理旁路关联，不动顺序号分配器——顺序号必须保持单调，回收会造成展示错序。
     */
    public void pruneOrderMetadata(Deque<LLMMessage> liveHistory) {
        if (liveHistory == null) {
            return;
        }
        synchronized (lock) {
            if (orderByMessage.isEmpty()) {
                return;
            }
            orderByMessage.keySet().removeIf(message -> !containsIdentity(liveHistory, message));
        }
    }

    private static boolean containsIdentity(Deque<LLMMessage> deque, LLMMessage target) {
        for (LLMMessage message : deque) {
            if (message == target) {
                return true;
            }
        }
        return false;
    }

    // ===== 独立记录追加 =====

    /** 追加一条独立记录（源消息与展示正文由调用方给出），返回新建记录 */
    public AutonomousChatRecord append(AutonomousChatRecord.Source source, UUID speakerId, String speakerName,
                                       long gameTime, String message, String chatText) {
        AutonomousChatRecord record;
        synchronized (lock) {
            record = AutonomousChatRecord.of(source, speakerId, speakerName, nextSeq++, gameTime, message, chatText);
            addInternal(record);
        }
        pruneArchiveIfServer();
        return record;
    }

    /**
     * 按既有消息身份追加一条记录（互聊对方的同一条发言写入对方视角）。
     * <p>
     * 复用 {@link AutonomousChatRecord#id()}——双方档案里存同一条消息、UUID 相同；
     * 顺序号按各自视角分别分配（所在视角的先后不同）。
     */
    public AutonomousChatRecord appendCopy(AutonomousChatRecord template) {
        if (template == null) {
            return null;
        }
        AutonomousChatRecord record;
        synchronized (lock) {
            record = template.copyWithSeq(nextSeq++);
            addInternal(record);
        }
        pruneArchiveIfServer();
        return record;
    }

    private void addInternal(AutonomousChatRecord record) {
        archive.addLast(record);
        if (record.source() == AutonomousChatRecord.Source.SELF_TALK
                || record.source() == AutonomousChatRecord.Source.WELCOME) {
            validSelfTalk.addLast(record);
            while (validSelfTalk.size() > VALID_CONTEXT_LIMIT) {
                validSelfTalk.pollFirst();
            }
        }
    }

    /** 展示档案容量裁剪：只在服务端按当前配置执行，客户端不按本地配置二次裁剪 */
    private void pruneArchiveIfServer() {
        if (clientSide()) {
            return;
        }
        int cap = Config.CHAT_HISTORY_MAX_STORED.get();
        synchronized (lock) {
            trimArchiveTo(cap);
        }
    }

    private void trimArchiveTo(int cap) {
        while (archive.size() > cap) {
            archive.pollFirst();
        }
    }

    /** 该消息身份是否已在本档案中（互聊幂等判定，绝不按正文相同判断） */
    public boolean containsMessageId(UUID id) {
        if (id == null) {
            return false;
        }
        synchronized (lock) {
            for (AutonomousChatRecord record : archive) {
                if (id.equals(record.id())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ===== 读取 =====

    /** 展示用记录（旧到新），已按隐藏边界过滤；不含被容量淘汰的部分 */
    public List<AutonomousChatRecord> visibleArchive() {
        synchronized (lock) {
            List<AutonomousChatRecord> result = new java.util.ArrayList<>(archive.size());
            for (AutonomousChatRecord record : archive) {
                if (record.seq() >= hiddenBelowSeq) {
                    result.add(record);
                }
            }
            return result;
        }
    }

    /** 有效自话上下文快照（旧到新），模型请求的唯一读点 */
    public List<AutonomousChatRecord> validSelfTalkSnapshot() {
        synchronized (lock) {
            return List.copyOf(validSelfTalk);
        }
    }

    /** 最新一条有效自话／欢迎语；没有则返回 null */
    public AutonomousChatRecord latestValidSelfTalk() {
        synchronized (lock) {
            return validSelfTalk.peekLast();
        }
    }

    public long hiddenBelowSeq() {
        synchronized (lock) {
            return hiddenBelowSeq;
        }
    }

    // ===== 遗忘与清空 =====

    /** 从有效上下文移除这些记录（自话达到保留阈值时只保留最新一条）；展示档案不受影响 */
    public void removeFromValidContext(Collection<AutonomousChatRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        synchronized (lock) {
            validSelfTalk.removeAll(records);
        }
    }

    /** 清空有效自话上下文（手动清空；展示档案只推进隐藏边界，不删除） */
    public void clearValidContext() {
        synchronized (lock) {
            validSelfTalk.clear();
        }
    }

    /**
     * 推进手动清空的隐藏边界到当前末尾：已有独立记录立即不展示、不进上下文，
     * 但仍在档案里（未超上限就不删除），顺序号分配器不重置。
     */
    public void advanceHiddenBoundary() {
        synchronized (lock) {
            hiddenBelowSeq = nextSeq;
        }
    }

    public boolean isMigrationDone() {
        synchronized (lock) {
            return migrationDone;
        }
    }

    public void markMigrationDone() {
        synchronized (lock) {
            migrationDone = true;
        }
    }

    /**
     * 在档案锁内执行一段迁移事务：迁移要「按 TLM 队列顺序分配顺序号 + 插入独立记录」两步原子完成，
     * 中间不得被其它线程的追加插进来，否则迁移记录与 TLM 历史消息的相对顺序就错了。
     * <p>
     * 只供 {@link AutonomousChatHistoryMigration} 使用；迁移本身在服务端主线程执行。
     */
    void runExclusive(Runnable action) {
        synchronized (lock) {
            action.run();
        }
    }

    /**
     * 迁移专用：为一条既有 TLM 历史消息登记顺序号（按队列旧到新的顺序调用）。
     *
     * @return 该消息的顺序号；已登记过则返回既有值（迁移可重入）
     */
    long assignSeqForMigration(LLMMessage message) {
        Long existing = orderByMessage.get(message);
        if (existing != null) {
            return existing;
        }
        long seq = nextSeq++;
        orderByMessage.put(message, seq);
        return seq;
    }

    /**
     * 迁移前：为整条 TLM 历史按「旧到新」一次性建立顺序号，只补未登记过的消息。
     * <p>
     * 旧存档不含本档案的 NBT 时，TLM 的 {@code readFromTag} 直接调 {@code CappedQueue.add} 恢复历史，
     * 不经过四个 {@code add*History} 的序号钩子，因此旧普通聊天与旧自话都没有序号。
     * 若迁移只给指纹命中的自话补号，普通消息就要等到 {@code writeTag} 才补到更大的号——
     * 原本的 {@code 普通 A → 自话 S → 普通 B} 会持久化成 {@code S(1) → A(2) → B(3)}，
     * 自话被挤到了它前面那条普通聊天之前，混合展示与模型历史都错序。
     * <p>
     * 因此在迁移真正搬动任何消息<b>之前</b>，按完整 deque 的旧到新顺序为所有消息补号；
     * 这样自话拿到的是它在原次序里的位置，与前后普通聊天保持相对顺序。
     * 已在钩子里登记过的消息保留原号（幂等，不重排既有次序）。
     */
    void assignSeqsForLegacyHistory(List<LLMMessage> dequeOldToNew) {
        if (dequeOldToNew == null || dequeOldToNew.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (LLMMessage message : dequeOldToNew) {
                if (message != null) {
                    assignSeqForMigration(message);
                }
            }
        }
    }

    /**
     * 迁移专用：以指定顺序号插入一条独立记录，保持与 TLM 历史的相对先后。
     * <p>
     * 迁移记录必须占用其源消息原有的顺序位置（按队列顺序分配），不能用当前分配器末尾的号——
     * 否则展示合并会把旧自话挤到所有旧历史之后。
     */
    void appendMigrated(AutonomousChatRecord.Source source, UUID speakerId, String speakerName,
                        long seq, long gameTime, String message, String chatText) {
        addInternal(new AutonomousChatRecord(UUID.randomUUID(), source, speakerId, speakerName,
                seq, gameTime, message, chatText));
    }

    public boolean isLoaded() {
        synchronized (lock) {
            return loaded;
        }
    }

    // ===== NBT =====

    /**
     * 写入自定义 tag。
     *
     * @param snapshot 与本次 TLM 历史序列<b>同一份</b>快照（由调用方从 writeToTag 内部
     *                 实际序列化的那一份捕获）。注意它的方向是 TLM 的序列化方向
     *                 ——{@code CappedQueue.add} 用 {@code offerFirst}，
     *                 {@code Lists.newArrayList(deque)} 按队头到队尾迭代，因此这份快照是「新到旧」；
     *                 本档案的顺序表统一按「旧到新」存放（与 {@link #readTag} 的入参同向），
     *                 下面反向遍历以完成这一步归一。
     */
    public void writeTag(CompoundTag parent, List<LLMMessage> snapshot) {
        CompoundTag tag = new CompoundTag();
        synchronized (lock) {
            tag.putInt(TAG_VERSION, DATA_VERSION);
            tag.putBoolean(TAG_MIGRATION_DONE, migrationDone);
            tag.putLong(TAG_HIDDEN_BELOW, hiddenBelowSeq);
            tag.put(TAG_ARCHIVE, saveRecords(archive));
            tag.put(TAG_VALID_SELF_TALK, saveRecords(validSelfTalk));
            // 顺序元数据必须与本次序列化的 TLM 历史逐位对应：
            // 只对同一份快照取值，绝不分别读取两次可变队列再假设下标一致。
            // 反向遍历把「新到旧」翻成「旧到新」——两处方向不一致会让读档后每个序号
            // 绑到相反的一端（正常重启／卸载重载即可触发），插在两者之间的自话合并位置随之错乱。
            int count = snapshot == null ? 0 : snapshot.size();
            long[] order = new long[count];
            for (int i = 0; i < count; i++) {
                LLMMessage message = snapshot.get(count - 1 - i);
                Long seq = orderByMessage.get(message);
                if (seq == null) {
                    // 旧存档未补号的消息：按旧到新的顺序一次性补号（保持单调且与时间顺序一致）
                    seq = nextSeq++;
                    orderByMessage.put(message, seq);
                }
                order[i] = seq;
            }
            tag.put(TAG_TLM_ORDER, new LongArrayTag(order));
            tag.putLong(TAG_NEXT_SEQ, nextSeq);
        }
        parent.put(NBT_KEY, tag);
    }

    /**
     * 读取自定义 tag 并按序列位置重新绑定顺序号。
     *
     * @param dequeInOrder 当前 TLM 历史（旧到新）——必须与序列化时的顺序同构
     */
    public void readTag(CompoundTag parent, List<LLMMessage> dequeInOrder) {
        if (parent == null || !parent.contains(NBT_KEY, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag tag = parent.getCompound(NBT_KEY);
        if (tag.getInt(TAG_VERSION) > DATA_VERSION) {
            // 版本更高的数据不做猜测性解释：保留 TLM 历史，独立档案按空处理
            MaidSelfTalkMod.LOGGER.warn("Autonomous chat history version {} is newer than {}, ignored",
                    tag.getInt(TAG_VERSION), DATA_VERSION);
            return;
        }
        synchronized (lock) {
            archive.clear();
            validSelfTalk.clear();
            orderByMessage.clear();
            for (AutonomousChatRecord record : loadRecords(tag.getList(TAG_ARCHIVE, Tag.TAG_COMPOUND))) {
                archive.addLast(record);
            }
            for (AutonomousChatRecord record : loadRecords(tag.getList(TAG_VALID_SELF_TALK, Tag.TAG_COMPOUND))) {
                validSelfTalk.addLast(record);
            }
            migrationDone = tag.getBoolean(TAG_MIGRATION_DONE);
            hiddenBelowSeq = tag.getLong(TAG_HIDDEN_BELOW);
            nextSeq = Math.max(1, tag.getLong(TAG_NEXT_SEQ));
            // 展示档案容量按当前配置裁剪（配置可能在保存之后被调小）
            if (!clientSide()) {
                trimArchiveTo(Config.CHAT_HISTORY_MAX_STORED.get());
            }
            // 按同一序列位置重新绑定顺序号：相同正文、相同 tick 的不同消息绝不合并
            bindOrderByPosition(tag, dequeInOrder == null ? List.of() : dequeInOrder);
            loaded = true;
        }
    }

    /**
     * 按位置绑定 TLM 历史消息的顺序号。
     * <p>
     * 长度不一致（旧存档没有该数据、或存档后又被追加）时，缺号的部分在原队列旧到新的
     * 顺序上一次性补号——补号仍保持单调，不会把已有次序打乱。
     */
    private void bindOrderByPosition(CompoundTag tag, List<LLMMessage> dequeInOrder) {
        long[] order = tag.getLongArray(TAG_TLM_ORDER);
        for (int i = 0; i < dequeInOrder.size(); i++) {
            LLMMessage message = dequeInOrder.get(i);
            long seq;
            if (i < order.length) {
                seq = order[i];
                if (seq >= nextSeq) {
                    nextSeq = seq + 1;
                }
            } else {
                seq = nextSeq++;
            }
            orderByMessage.put(message, seq);
        }
    }

    private static ListTag saveRecords(Deque<AutonomousChatRecord> records) {
        ListTag list = new ListTag();
        for (AutonomousChatRecord record : records) {
            list.add(record.save());
        }
        return list;
    }

    private static List<AutonomousChatRecord> loadRecords(ListTag list) {
        List<AutonomousChatRecord> result = new java.util.ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            AutonomousChatRecord record = AutonomousChatRecord.load(list.getCompound(i));
            if (record != null) {
                result.add(record);
            }
        }
        return result;
    }
}
