package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.WaitingChatBubbleData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 等待气泡来源标记的类型 ID 与序列化集中管理（issue #78）。
 * <p>
 * 新增类型 {@code maid_self_talk:autonomous_waiting} 承担「自话/互聊等待气泡」的线上区分：
 * 序列化内容完全委托 TLM 原生 {@link WaitingChatBubbleData.WaitingChatSerializer}；
 * 反序列化后恢复来源标记。不新增独立 S2C 来源包、不维护气泡 ID 映射表。
 * <p>
 * 标记后的对象仍是 {@link WaitingChatBubbleData}，仍产生原生 {@code WaitingChatBubbleRenderer}，
 * 不创建包装气泡类、不替代 renderer。
 */
public final class SelfTalkWaitingBubbles {

    /** 专用气泡类型 ID：服务端到客户端的实体数据同步携带它，客户端渲染据此过滤 */
    public static final ResourceLocation AUTONOMOUS_WAITING_ID =
            new ResourceLocation(MaidSelfTalkMod.MODID, "autonomous_waiting");

    private static final WaitingChatBubbleData.WaitingChatSerializer NATIVE =
            new WaitingChatBubbleData.WaitingChatSerializer();

    /** 已注册的 serializer 入口：写委托原生、读恢复标记，类型 ID 已承担区分作用 */
    public static final IChatBubbleData.ChatSerializer SERIALIZER = new IChatBubbleData.ChatSerializer() {
        @Override
        public IChatBubbleData readFromBuff(FriendlyByteBuf buf) {
            return SelfTalkWaitingBubbles.readFromBuff(buf);
        }

        @Override
        public void writeToBuff(FriendlyByteBuf buf, IChatBubbleData data) {
            SelfTalkWaitingBubbles.writeToBuff(buf, data);
        }
    };

    private SelfTalkWaitingBubbles() {
    }

    /** 写入完全委托原生等待气泡 serializer */
    public static void writeToBuff(FriendlyByteBuf buf, IChatBubbleData data) {
        NATIVE.writeToBuff(buf, data);
    }

    /** 读取先委托原生 serializer，再给返回的原生等待气泡设置来源标记 */
    public static IChatBubbleData readFromBuff(FriendlyByteBuf buf) {
        IChatBubbleData data = NATIVE.readFromBuff(buf);
        if (data instanceof SelfTalkWaitingBubbleData marker) {
            marker.setAutonomousWaiting(true);
        }
        return data;
    }

    /**
     * 标记某一回调当前持有的等待气泡为自话/互聊来源（服务端主线程调用）。
     * <p>
     * 只处理 {@link InterChatCallback} 或非欢迎的 {@link SelfTalkCallback}；按回调的
     * {@code getWaitingChatBubbleId()} 从所属女仆管理器取气泡，只标记实际存在的原生等待气泡；
     * 标记后调用 {@code forceUpdateChatBubble()} 保证实体数据包含标记。不新增、删除、替换气泡，
     * 不改变 ID，也不扫描整只女仆的其他气泡。
     */
    public static void markBubble(LLMCallback callback) {
        boolean target = callback instanceof InterChatCallback
                || (callback instanceof SelfTalkCallback self && !self.isWelcome());
        if (!target) {
            return;
        }
        long id = callback.getWaitingChatBubbleId();
        if (id == 0) {
            return;
        }
        var maid = callback.getMaid();
        if (maid == null || !maid.isAlive()) {
            return;
        }
        var manager = maid.getChatBubbleManager();
        var bubble = manager.getChatBubble(id);
        if (!(bubble instanceof SelfTalkWaitingBubbleData marker)) {
            return;
        }
        marker.setAutonomousWaiting(true);
        manager.forceUpdateChatBubble();
    }

    /** 供 TLM 扩展入口注册使用：新 ID + serializer */
    public static void register(com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleRegister register) {
        register.register(AUTONOMOUS_WAITING_ID, SERIALIZER);
        MaidSelfTalkMod.LOGGER.info("maid_self_talk: autonomous_waiting chat bubble registered");
    }
}