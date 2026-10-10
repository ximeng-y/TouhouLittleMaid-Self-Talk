package com.maidmod.selftalk;

import net.minecraft.nbt.CompoundTag;

import java.util.Optional;
import java.util.UUID;

/**
 * 一条独立归档记录：自话、欢迎语或互聊的一轮发言。
 * <p>
 * 本记录是「展示档案」与「有效自话上下文」的最小单元，与 TLM 历史 deque 中的消息彼此独立：
 * 同一轮自话不再进入 TLM 历史，但通过 {@link #seq} 与 TLM 历史共用同一套单调顺序号，
 * 从而让历史界面能把两者按真实发生顺序合并展示。
 * <p>
 * 字段语义：
 * <ul>
 *   <li>{@link #id}：稳定消息 UUID，用于同一次交付的幂等判定。互聊双方各自的档案里存同一条消息、
 *       UUID 相同，但顺序号按各自视角分别分配——<b>绝不能用正文相同来判定「同一条消息」</b>；</li>
 *   <li>{@link #source}：来源（自话／欢迎语／互聊）；</li>
 *   <li>{@link #speakerId}/{@link #speakerName}：发言女仆 UUID 与发言时的显示名称。
 *       名称只作为元数据与界面标签，绝不拼进存储正文、TTS 文本或发送给模型的原话；</li>
 *   <li>{@link #seq}：本记录在所属女仆视角下的单调顺序号，由 {@link AutonomousChatHistory} 分配；</li>
 *   <li>{@link #message}：进入模型上下文的原消息。自话保留原 {@code ResponseChat.toString()} 形态
 *       （聊天／TTS 两段内容的形式不变），互聊为纯聊天文本；</li>
 *   <li>{@link #chatText}：界面展示正文，只含聊天文本（无 TTS 段、无段标签、无内部标识）。</li>
 * </ul>
 * 记录本身不可变；顺序号在创建时确定，之后不改写。
 */
public record AutonomousChatRecord(UUID id, Source source, UUID speakerId, String speakerName,
                                   long seq, long gameTime, String message, String chatText) {

    /** 独立记录的来源 */
    public enum Source {
        SELF_TALK("self_talk"),
        WELCOME("welcome"),
        INTER_CHAT("inter_chat");

        private final String key;

        Source(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** 未知键返回空（读档容错：不因未来新增来源而整体丢弃档案） */
        public static Optional<Source> byKey(String key) {
            for (Source source : values()) {
                if (source.key.equals(key)) {
                    return Optional.of(source);
                }
            }
            return Optional.empty();
        }
    }

    /** 创建一条新记录（顺序号由存储分配后传入） */
    public static AutonomousChatRecord of(Source source, UUID speakerId, String speakerName,
                                          long seq, long gameTime, String message, String chatText) {
        return new AutonomousChatRecord(UUID.randomUUID(), source, speakerId, speakerName,
                seq, gameTime, message, chatText);
    }

    /**
     * 按同一消息身份换一个视角顺序号复制（互聊对方视角写入用）。
     * 消息 UUID 保持相同——双方档案里是同一条消息；顺序号按各自视角分别分配。
     */
    public AutonomousChatRecord copyWithSeq(long newSeq) {
        return new AutonomousChatRecord(id, source, speakerId, speakerName, newSeq, gameTime, message, chatText);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Source", source.key());
        if (speakerId != null) {
            tag.putUUID("Speaker", speakerId);
        }
        tag.putString("SpeakerName", speakerName == null ? "" : speakerName);
        tag.putLong("Seq", seq);
        tag.putLong("GameTime", gameTime);
        tag.putString("Message", message == null ? "" : message);
        tag.putString("ChatText", chatText == null ? "" : chatText);
        return tag;
    }

    /** 读档；关键字段缺失（无 Id 或来源不可识别）时返回 null，由调用方跳过该条 */
    public static AutonomousChatRecord load(CompoundTag tag) {
        if (!tag.hasUUID("Id")) {
            return null;
        }
        Optional<Source> source = Source.byKey(tag.getString("Source"));
        if (source.isEmpty()) {
            return null;
        }
        return new AutonomousChatRecord(tag.getUUID("Id"), source.get(),
                tag.hasUUID("Speaker") ? tag.getUUID("Speaker") : null,
                tag.getString("SpeakerName"),
                tag.getLong("Seq"), tag.getLong("GameTime"),
                tag.getString("Message"), tag.getString("ChatText"));
    }
}
