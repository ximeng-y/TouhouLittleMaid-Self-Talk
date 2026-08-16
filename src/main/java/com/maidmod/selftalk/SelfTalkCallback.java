package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.UUID;

/**
 * 自言自语专用的 LLM 回调。
 * <p>
 * 与玩家 chat 的裸 {@link LLMCallback} 的区别：
 * <ul>
 *   <li>{@code needAddTools = false}：模型看不到任何工具定义，天然不会调用 tool（无需在提示词中禁止）；</li>
 *   <li>{@code shouldCacheTokenUsage} 保持默认 {@code true}：自话会更新女仆的 lastChatTokenUsage，
 *       从而占用上下文压缩额度——但压缩只在玩家 chat 时触发（tryCompressBeforeChat 仅由 chat() 调用），
 *       因此自话即使撑大上下文也不会立即触发压缩；</li>
 *   <li>onSuccess 后广播 {@link MaidChatReplyEvent} 并执行遗忘检查。</li>
 * </ul>
 * <p>
 * 玩家的 token 配额记账发生在 LLM 客户端响应层（LLMOpenAIClient），与回调类型无关：
 * 只要女仆的主人是在线玩家（ServerPlayer），自话产生的 token 会自动计入主人的配额。
 */
public class SelfTalkCallback extends LLMCallback {

    private final boolean welcome;
    /** 自话窗口保留条数上限（触发遗忘的阈值），由触发方按当前态传入 */
    private final int keepSelfTalkCount;
    /** 聊天框广播半径（格）：范围内存活玩家可见自话内容 */
    private final double broadcastRange;
    /** 本次回复的 assistant 消息（响应线程在父类写历史后立即捕获，供遗忘机制识别） */
    private LLMMessage lastAssistantMessage;

    public SelfTalkCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                            boolean welcome, int keepSelfTalkCount, double broadcastRange) {
        super(chatManager, messages);
        this.welcome = welcome;
        this.keepSelfTalkCount = keepSelfTalkCount;
        this.broadcastRange = broadcastRange;
        // 模型看不到工具定义，天然不会调用任何 tool
        this.needAddTools = false;
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // TLM 默认行为：写 assistant 历史（供聊天记录 UI 显示）、显示气泡并给主人发送聊天栏消息
        super.onSuccess(responseChat);
        // 父类对空白回复（chatText/ttsText 为空）会内部转调 onFailure 并 return，不写历史；
        // 此处短路，避免继续走成功路径的 finish（空文本广播 + 把陈旧消息误当本次回复计入窗口）
        if (responseChat.getChatText().isBlank() || responseChat.getTtsText().isBlank()) {
            return;
        }
        // 响应线程、父类写历史之后立即捕获本次回复（父类刚写入队头，本线程写后立即读，必为本次回复）。
        // 不能到主线程再 peek——自话与玩家 chat 回复同一瞬间完成时可能取到玩家消息
        this.lastAssistantMessage = getChatManager().getHistory().getDeque().peekFirst();
        EntityMaid maid = getMaid();
        Runnable finish = () -> {
            NeoForge.EVENT_BUS.post(new MaidChatReplyEvent(maid, responseChat.getChatText(), welcome));
            MaidSelfTalkService.onSelfTalkFinished(maid, this);
            broadcastToNearby(maid, responseChat.getChatText());
        };
        if (isOnServerThread()) {
            finish.run();
        } else {
            // LLM 回调在响应线程，状态与事件必须回到服务端主线程
            runOnServerThread(finish);
        }
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        super.onFailure(request, throwable, errorCode);
        runOnServerThread(() -> SelfTalkState.get(getMaid().getId()).selfTalkPending = false);
    }

    /**
     * 自话内容广播到附近玩家的聊天框，格式与原版聊天一致：{@code <女仆名> 内容}。
     * <p>
     * 注意：TLM 的 {@code ChatBubbleManager.addLLMChatText}（onSuccess 父类逻辑）已会给<b>主人</b>
     * 发送同格式聊天栏消息，此处跳过主人，只广播给其他附近玩家，避免消息重复。
     * 只在服务端主线程调用。
     */
    private void broadcastToNearby(EntityMaid maid, String chatText) {
        if (!(maid.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        UUID ownerUuid = maid.getOwnerUUID();
        // 与 TLM addLLMChatText 相同格式（灰色 <女仆名> 内容）
        Component message = Component.literal("<")
                .append(maid.getName())
                .append("> ")
                .append(chatText)
                .withStyle(ChatFormatting.GRAY);
        AABB box = maid.getBoundingBox().inflate(broadcastRange);
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, box,
                p -> p.isAlive() && !p.isSpectator() && !p.getUUID().equals(ownerUuid))) {
            player.sendSystemMessage(message);
        }
    }

    public boolean isWelcome() {
        return welcome;
    }

    public int getKeepSelfTalkCount() {
        return keepSelfTalkCount;
    }

    /** 本次回复的 assistant 消息（可能为 null：空白回复等未写历史的路径） */
    public LLMMessage getLastAssistantMessage() {
        return lastAssistantMessage;
    }
}
