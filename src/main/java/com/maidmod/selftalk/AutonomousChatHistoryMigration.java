package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * 旧数据迁移：把 TLM 历史里<b>已由旧指纹明确标记</b>的自话 assistant 消息搬进独立档案。
 * <p>
 * 与「新格式恢复」（{@link AutonomousChatHistory#readTag}）分开：读档只负责恢复新格式，
 * 不假设附件等运行期数据已就绪；迁移则在服务端主线程、由下面四处入口按需触发，
 * 幂等且一次生效（{@link AutonomousChatHistory#isMigrationDone()}）。
 * <p>
 * 触发入口（计划指定）：
 * <ul>
 *   <li>首次构造模型历史（玩家 chat 组装前缀时）；</li>
 *   <li>首次压缩（{@code tryScheduleHistorySummary} 开始处，自动与手动压缩共用路径）；</li>
 *   <li>首次服务端 AI 数据保存／同步（{@code writeToTag}）；</li>
 *   <li>手动清空前（{@code clearAllChatMemory}）。</li>
 * </ul>
 * 迁移不受自话总开关是否开启影响——关闭自话只是不再产生新记录，旧数据仍须归档。
 * <p>
 * 迁移规则（严格按计划，不做任何猜测）：
 * <ol>
 *   <li>按原 TLM 历史顺序分配顺序号；</li>
 *   <li>只迁移旧自话指纹明确命中的普通 assistant 消息（非工具调用、正文非空白）；</li>
 *   <li>加入独立展示档案与有效自话上下文，保留原消息格式与原相对顺序；</li>
 *   <li>独立数据接收完成后，再从 TLM 队列移除这些具体消息；</li>
 *   <li>同步清理已迁移的旧指纹、失效检索缓存，并记录迁移完成；</li>
 *   <li>来源不明的旧 assistant 留在 TLM 历史——不按角色、文本或相邻关系猜测；</li>
 *   <li>旧互聊原本未持久化，没有可迁移记录时不编造；已混入旧摘要的内容不拆除。</li>
 * </ol>
 * <p>
 * 迁移完成标记一旦落位就永不再跑：即便旧指纹被后续清理，也不会把同一批消息搬第二次。
 */
public final class AutonomousChatHistoryMigration {

    private AutonomousChatHistoryMigration() {
    }

    /**
     * 幂等迁移入口（服务端主线程）。
     * <p>
     * 迁移会把消息移出 TLM 队列，因此在响应线程／异步线程调用会与主线程的玩家 chat 交错——
     * 这里自行判定线程与侧别，非服务端主线程一律不改动任何数据。
     */
    public static void ensureMigrated(EntityMaid maid) {
        if (maid == null || maid.level() == null || maid.level().isClientSide()) {
            return;
        }
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null) {
            return;
        }
        AutonomousChatHistory history = AutonomousChatHistoryHost.of(chatManager);
        if (history == null || history.isMigrationDone()) {
            return;
        }
        ServerLevel level = maid.level() instanceof ServerLevel server ? server : null;
        if (level == null || level.getServer() == null || !level.getServer().isSameThread()) {
            // 非主线程：延后到主线程执行（保存快照等调用点可能来自其它线程），
            // 本次不迁移也不做任何部分状态修改
            if (level != null && level.getServer() != null) {
                level.getServer().execute(() -> ensureMigrated(maid));
            }
            return;
        }
        migrate(maid, chatManager, history);
    }

    /**
     * 实际迁移（必须在服务端主线程）。
     * <p>
     * 已在主线程的调用点（玩家 chat 组装、压缩、清空）直接调本方法，避免经
     * {@code server.execute} 延后一 tick —— 那些路径必须「迁移先于本次组装／删除看到结果」。
     */
    public static void ensureMigratedOnServerThread(EntityMaid maid) {
        if (maid == null || maid.level() == null || maid.level().isClientSide()) {
            return;
        }
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null) {
            return;
        }
        AutonomousChatHistory history = AutonomousChatHistoryHost.of(chatManager);
        if (history == null || history.isMigrationDone()) {
            return;
        }
        ServerLevel level = maid.level() instanceof ServerLevel server ? server : null;
        if (level == null || level.getServer() == null || !level.getServer().isSameThread()) {
            ensureMigrated(maid);
            return;
        }
        migrate(maid, chatManager, history);
    }

    private static void migrate(EntityMaid maid, MaidAIChatManager chatManager, AutonomousChatHistory history) {
        // 惰性初始化 legacy 快照（老会话标记为段外）并顺带剪枝，保证下面的指纹判定与段标签同源
        Deque<LLMMessage> deque = chatManager.getHistory().getDeque();
        if (deque.isEmpty()) {
            // 没有历史可迁移：仍记录迁移完成，避免每次组装都重走一遍
            history.markMigrationDone();
            return;
        }
        SelfTalkProvenance.ensureLegacyInitialized(maid, deque);
        Set<String> fingerprints = SelfTalkProvenance.selfTalkFingerprints(maid);
        if (fingerprints.isEmpty()) {
            history.markMigrationDone();
            return;
        }
        // 一次取同源快照：迁移要按这份顺序分配顺序号并从队列删除这些具体消息。
        // TLM 的 CappedQueue.add 用 offerFirst，队头是最新消息，直接迭代得到的是「新到旧」；
        // 必须用 descendingIterator 翻成真正的「旧到新」——否则递增序号会按时间倒序分配，
        // 档案与有效上下文被整体反转，latestValidSelfTalk() 甚至会把最旧的一条当成最新自话
        // 注入精简／检索请求。该错误随迁移完成标记持久化，不是一次性的界面排序问题。
        List<LLMMessage> snapshot = new ArrayList<>(deque.size()); // 旧到新
        deque.descendingIterator().forEachRemaining(snapshot::add);
        String speakerName = AutonomousChatHistoryHost.displayNameOf(maid);
        List<LLMMessage> migrated = new ArrayList<>();
        List<String> migratedFingerprints = new ArrayList<>();
        history.runExclusive(() -> {
            for (LLMMessage message : snapshot) {
                if (!isMigratableSelfTalk(message, fingerprints)) {
                    continue;
                }
                long seq = history.assignSeqForMigration(message);
                String text = message.message();
                history.appendMigrated(AutonomousChatRecord.Source.SELF_TALK, maid.getUUID(), speakerName,
                        seq, message.gameTime(), text, chatTextOf(text));
                migrated.add(message);
                migratedFingerprints.add(SelfTalkProvenance.fingerprint(message));
            }
        });
        // 独立数据接收完成后才从 TLM 队列移除（顺序不可颠倒：先删后写会在中途失败时丢内容）
        if (!migrated.isEmpty()) {
            deque.removeAll(migrated);
        }
        // 已迁移的旧指纹同步清理；检索缓存失效（TLM 历史变了）
        SelfTalkProvenance.removeFingerprints(maid, migratedFingerprints);
        HistoryRetrievalCache.invalidate(maid);
        history.markMigrationDone();
        if (!migrated.isEmpty()) {
            MaidSelfTalkMod.LOGGER.info("Migrated {} legacy self-talk message(s) into autonomous archive for maid {}",
                    migrated.size(), maid.getId());
        }
    }

    /**
     * 该消息是否是可迁移的旧自话：必须由旧指纹明确命中。
     * <p>
     * 工具相关的 assistant 与空白正文一律不动（既不能进展示档案，也不是有效自话上下文）；
     * 旧互聊从未持久化，因此不存在可迁移的互聊记录。
     */
    private static boolean isMigratableSelfTalk(LLMMessage message, Set<String> fingerprints) {
        if (message.role() != Role.ASSISTANT) {
            return false;
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            return false;
        }
        String text = message.message();
        if (text == null || text.isBlank()) {
            return false;
        }
        return fingerprints.contains(SelfTalkProvenance.fingerprint(message));
    }

    /**
     * 原消息 → 界面展示正文：自话上下文保留原 {@code ResponseChat.toString()} 形态，
     * 界面只展示抽取出的聊天文本（无 TTS 段、无段标签）。
     * <p>
     * 解析失败时退回原文本——迁移不得因单条格式异常而丢失内容。
     */
    private static String chatTextOf(String message) {
        try {
            String chatText = new com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat(message)
                    .getChatText();
            return chatText == null || chatText.isBlank() ? message : chatText;
        } catch (Throwable t) {
            return message;
        }
    }

    /** 服务端 AI 数据序列化前的迁移入口（{@code writeToTag} 内调用，可能来自非主线程） */
    public static void ensureMigratedForSave(MaidAIChatData data) {
        if (data != null) {
            ensureMigrated(data.getMaid());
        }
    }
}
