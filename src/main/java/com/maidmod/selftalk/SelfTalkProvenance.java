package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 自话/互聊来源判定（段标签）的持久化指纹管理。
 * <p>
 * 历史 deque 中自话回复与主人聊天回复形态相同（都是含 "---" 的 ASSISTANT 消息），
 * 且 TLM 的 NBT 不记录来源；本类在<b>自话回复写入历史的那一刻</b>登记指纹，
 * wrap 时据此把历史切分为「自话段 / 主人段」，老版本（无指纹）历史由 legacy 快照标记为段外。
 * <p>
 * 线程约定：登记与删除可在 LLM 响应线程（自话回调、TLM 压缩回调）执行，
 * 读取在主线程（wrap）；载体为实体字段并发集（{@link SelfTalkProvenanceHost}），跨线程安全。
 * 持久化由 {@code MaidAIChatDataMixin} 在女仆 NBT 读写时完成，本类只操作内存。
 */
public final class SelfTalkProvenance {

    /**
     * 剪枝触发余量（条）：集合规模超出「当前历史条数 + 余量」才剪枝，
     * 容忍在途登记与窗口消息的短时抖动，避免每次 wrap 都全量重建。
     */
    private static final int PRUNE_SLACK = 64;

    private SelfTalkProvenance() {
    }

    /**
     * 消息指纹：唯一标识一条历史消息的三元组拼接串（带长度前缀防歧义）。
     * 无碰撞（三元组相等 ⇔ 字符串相等），排除哈希碰撞误判的可能。
     */
    static String fingerprint(LLMMessage message) {
        String content = message.message() == null ? StringUtils.EMPTY : message.message();
        return message.role().name() + '|' + content.length() + '|'
                + content + '|' + message.gameTime();
    }

    /**
     * 登记一条自话回复的指纹（写入历史后、同一响应线程调用）。
     */
    public static void registerSelfTalk(EntityMaid maid, LLMMessage message) {
        if (maid == null || message == null) {
            return;
        }
        ((SelfTalkProvenanceHost) maid).maid_self_talk$selfFingerprints().add(fingerprint(message));
    }

    /**
     * 按消息集合删除指纹（与历史消息从 deque 移除同步：自话 trim、TLM 压缩）。
     */
    public static void removeByMessages(EntityMaid maid, Collection<LLMMessage> messages) {
        if (maid == null || messages == null || messages.isEmpty()) {
            return;
        }
        var fingerprints = ((SelfTalkProvenanceHost) maid).maid_self_talk$selfFingerprints();
        for (LLMMessage message : messages) {
            fingerprints.remove(fingerprint(message));
        }
    }

    /** 清空全部指纹与 legacy 标记（历史清空时调用；客户端 no-op） */
    public static void clearAll(EntityMaid maid) {
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        ((SelfTalkProvenanceHost) maid).maid_self_talk$clearProvenanceData();
    }

    /**
     * 一次性初始化老会话快照：把当前历史中<b>没有自话指纹</b>的消息登记为 legacy（段外）。
     * <p>
     * 首次 wrap 时调用——此时历史快照=升级后（或存量）已存在的全部会话，
     * 恰好对应「老版本会话不进 XML」；此后永不重初始化（升级点时的新消息走正常判定）。
     * 已登记指纹的消息（升级后新产生的自话）不纳入 legacy。
     */
    public static void ensureLegacyInitialized(EntityMaid maid, Deque<LLMMessage> historyDeque) {
        if (maid == null || historyDeque == null) {
            return;
        }
        SelfTalkProvenanceHost host = (SelfTalkProvenanceHost) maid;
        if (host.maid_self_talk$legacyInitialized()) {
            return;
        }
        var selfTalkFingerprints = host.maid_self_talk$selfFingerprints();
        var legacy = host.maid_self_talk$legacyFingerprints();
        for (LLMMessage message : historyDeque) {
            String fp = fingerprint(message);
            if (!selfTalkFingerprints.contains(fp)) {
                legacy.add(fp);
            }
        }
        host.maid_self_talk$setLegacyInitialized(true);
    }

    /**
     * 指纹集膨胀剪枝：TLM 历史 CappedQueue 容量满时静默逐出最旧消息（pollLast，无任何回调），
     * 被逐出消息的指纹不经由任何删除钩子，长期运行下只增不减（指纹含完整正文，随 NBT 持久化）。
     * 此处以当前完整历史为基准惰性剪枝：集合规模超出「历史条数 + 余量」时，
     * 丢弃历史中已不存在的指纹（legacy 快照同理——消息没了，快照条目即死数据）。
     * 调用点：wrapSegments（服务端主线程，随每次 LLM 请求触发），无需独立计时器。
     */
    static void pruneIfBloated(EntityMaid maid, Deque<LLMMessage> historyDeque) {
        if (maid == null || historyDeque.isEmpty()) {
            return;
        }
        SelfTalkProvenanceHost host = (SelfTalkProvenanceHost) maid;
        Set<String> selfTalk = host.maid_self_talk$selfFingerprints();
        Set<String> legacy = host.maid_self_talk$legacyFingerprints();
        int alive = historyDeque.size();
        if (selfTalk.size() <= alive + PRUNE_SLACK && legacy.size() <= alive + PRUNE_SLACK) {
            return;
        }
        Set<String> aliveFingerprints = new HashSet<>(alive * 2);
        for (LLMMessage message : historyDeque) {
            aliveFingerprints.add(fingerprint(message));
        }
        selfTalk.retainAll(aliveFingerprints);
        legacy.retainAll(aliveFingerprints);
    }
}
