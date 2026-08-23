package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Deque;
import java.util.Set;

/**
 * 自话/互聊来源判定（段标签）的持久化指纹管理。
 * <p>
 * 历史 deque 中自话回复与主人聊天回复形态相同（都是含 "---" 的 ASSISTANT 消息），
 * 且 TLM 的 NBT 不记录来源；本类在<b>自话回复写入历史的那一刻</b>登记指纹，
 * wrap 时据此把历史切分为「自话段 / 主人段」，老版本（无指纹）历史由 legacy 快照标记为段外。
 * <p>
 * 线程约定：登记与删除可在 LLM 响应线程（自话回调、TLM 压缩回调）执行，
 * 读取在主线程（wrap）；附件集合为并发集，跨线程安全。
 */
public final class SelfTalkProvenance {

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
        maid.getData(SelfTalkAttachments.SELF_TALK_FINGERPRINTS).add(fingerprint(message));
    }

    /**
     * 按消息集合删除指纹（与历史消息从 deque 移除同步：自话 trim、TLM 压缩）。
     */
    public static void removeByMessages(EntityMaid maid, Collection<LLMMessage> messages) {
        if (maid == null || messages == null || messages.isEmpty()) {
            return;
        }
        maid.getExistingData(SelfTalkAttachments.SELF_TALK_FINGERPRINTS).ifPresent(set -> {
            for (LLMMessage message : messages) {
                set.remove(fingerprint(message));
            }
        });
    }

    /** 清空全部指纹与 legacy 标记（历史清空时调用；客户端 no-op） */
    public static void clearAll(EntityMaid maid) {
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        maid.getExistingData(SelfTalkAttachments.SELF_TALK_FINGERPRINTS)
                .ifPresent(Set::clear);
        maid.getExistingData(SelfTalkAttachments.LEGACY_SEGMENT_FINGERPRINTS)
                .ifPresent(Set::clear);
        if (maid.hasData(SelfTalkAttachments.SEGMENT_LEGACY_INITIALIZED)) {
            maid.setData(SelfTalkAttachments.SEGMENT_LEGACY_INITIALIZED, false);
        }
    }

    /** 消息是否登记为自话回复（历史区归「自话/互聊段」） */
    public static boolean isSelfTalkMessage(EntityMaid maid, LLMMessage message) {
        return maid.getExistingData(SelfTalkAttachments.SELF_TALK_FINGERPRINTS)
                .map(set -> set.contains(fingerprint(message))).orElse(false);
    }

    /** 消息是否属于老会话快照（段外原样，不进 XML） */
    public static boolean isLegacyMessage(EntityMaid maid, LLMMessage message) {
        return maid.getExistingData(SelfTalkAttachments.LEGACY_SEGMENT_FINGERPRINTS)
                .map(set -> set.contains(fingerprint(message))).orElse(false);
    }

    /**
     * 一次性初始化老会话快照：把当前历史中<b>没有自话指纹</b>的消息登记为 legacy（段外）。
     * <p>
     * 首次 wrap 时调用——此时历史快照=升级后（或存量）已存在的全部会话，
     * 恰好对应「老版本会话不进 XML」；此后永不重初始化（升级点时的新消息走正常判定）。
     * 已登记指纹的消息（升级后新产生的自话）不纳入 legacy。
     */
    public static void ensureLegacyInitialized(EntityMaid maid, Deque<LLMMessage> historyDeque) {
        if (maid == null || historyDeque == null
                || maid.getData(SelfTalkAttachments.SEGMENT_LEGACY_INITIALIZED)) {
            return;
        }
        Set<String> selfTalkFingerprints = maid.getExistingData(SelfTalkAttachments.SELF_TALK_FINGERPRINTS)
                .orElse(Set.of());
        Set<String> legacy = maid.getData(SelfTalkAttachments.LEGACY_SEGMENT_FINGERPRINTS);
        for (LLMMessage message : historyDeque) {
            String fp = fingerprint(message);
            if (!selfTalkFingerprints.contains(fp)) {
                legacy.add(fp);
            }
        }
        maid.setData(SelfTalkAttachments.SEGMENT_LEGACY_INITIALIZED, true);
    }
}
