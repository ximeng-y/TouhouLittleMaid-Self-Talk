package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraftforge.eventbus.api.Event;

/**
 * 女仆 AI 真实回复事件（服务端主线程发布）。
 * <p>
 * 本模组的自言自语/欢迎语回复均会发布此事件，携带女仆与回复文本。
 * 该事件同时是「女仆互相对话」的预留扩展接口：
 * 后续实现互相对话时，监听此事件即可把 A 女仆的回复作为 B 女仆的对话输入。
 */
public class MaidChatReplyEvent extends Event {

    private final EntityMaid maid;
    /** AI 回复的纯文本内容（不含 TTS 文本） */
    private final String chatText;
    /** 是否为欢迎语 */
    private final boolean welcome;

    public MaidChatReplyEvent(EntityMaid maid, String chatText, boolean welcome) {
        this.maid = maid;
        this.chatText = chatText;
        this.welcome = welcome;
    }

    public EntityMaid getMaid() {
        return maid;
    }

    public String getChatText() {
        return chatText;
    }

    public boolean isWelcome() {
        return welcome;
    }
}
