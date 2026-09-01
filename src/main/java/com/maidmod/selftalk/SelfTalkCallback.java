package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 自言自语专用的 LLM 回调。
 * <p>
 * 与玩家 chat 的裸 {@link LLMCallback} 的区别：
 * <ul>
 *   <li>{@code needAddTools} 由 Tool 开关判定决定（默认 false）：关闭时模型看不到任何工具定义，
 *       天然不会调用 tool；开启时 TLM 原生工具循环接管（中间轮不上屏，仅思考气泡副文本），
 *       最终 onSuccess 的回答才走本 mod 的广播；</li>
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
    /**
     * 本轮工具过程写入 TLM 历史的消息引用（assistant(tool_calls) 与 tool 结果）。
     * 响应线程写、主线程删（deque 为 LinkedBlockingDeque，跨线程安全）；
     * 最终回答后成对全删——「本轮工具相关的全删」天然保证配对，绝不留下孤立的
     * assistant(tool_calls) 或 tool（孤立记录会让后续请求被 LLM 服务端 400 拒绝）。
     * <p>
     * 已知边界：TLM 的第三方 sub-agent 工具（EXTENSIONS.registerAITool 返回异体回调时）
     * 在 {@code LLMCallback.executeSingleToolCall} 内直接 addToolHistory 占位结果，
     * 不经过本类的 addToolResult 覆盖——该占位 tool 消息不会被捕获/删除，心跳也不刷新
     * （TLM 自带 7 个工具全部经 addToolResult 返回同体回调，不触发此路径）。
     * 残留记录天然构成完整 tool_calls+tool 对，无 400 风险；pending 由 5 分钟超时兜底复位。
     */
    private final List<LLMMessage> toolHistoryMessages = new ArrayList<>();

    public SelfTalkCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                            boolean welcome, int keepSelfTalkCount, double broadcastRange, boolean toolEnabled) {
        super(chatManager, messages);
        this.welcome = welcome;
        this.keepSelfTalkCount = keepSelfTalkCount;
        this.broadcastRange = broadcastRange;
        // Tool 关闭时模型看不到工具定义；开启时由 TLM 工具循环接管
        this.needAddTools = toolEnabled;
    }

    /**
     * 工具轮次：父类写 assistant(tool_calls) 历史后捕获队头引用，供最终回答后成对删除。
     * CappedQueue 新消息在队头（offerFirst）。
     */
    @Override
    public void onFunctionCall(Message choice, LLMClient client) {
        super.onFunctionCall(choice, client);
        captureToolHistoryHead(Role.ASSISTANT);
    }

    /**
     * 工具结果：父类写 tool 历史后捕获队头引用，并刷新 pending 心跳
     * （语义从「请求发起后 5 分钟超时」变为「最后一次工具活动后 5 分钟超时」，
     * 防止 16 轮工具链被 SelfTalkHandler 的超时兜底误判空闲而并发派发第二个请求）。
     */
    @Override
    public LLMCallback addToolResult(String result, String toolId) {
        LLMCallback cb = super.addToolResult(result, toolId);
        captureToolHistoryHead(Role.TOOL);
        refreshPendingHeartbeat();
        return cb;
    }

    /** 捕获刚写入历史的工具相关消息（队头 + role 校验，防交错抓取） */
    private void captureToolHistoryHead(Role expected) {
        LLMMessage head = getChatManager().getHistory().getDeque().peekFirst();
        if (head != null && head.role() == expected) {
            toolHistoryMessages.add(head);
        }
    }

    /** 最终回答（或失败）后删除本轮全部工具过程消息；删完历史等价于「没发生过」，前缀缓存不受损 */
    private void discardToolHistory() {
        if (toolHistoryMessages.isEmpty()) {
            return;
        }
        getChatManager().getHistory().getDeque().removeAll(toolHistoryMessages);
        toolHistoryMessages.clear();
    }

    /** 工具轮次心跳：刷新 pending 起始 tick（须在服务端主线程写状态） */
    private void refreshPendingHeartbeat() {
        Runnable beat = () -> {
            SelfTalkState.State state = SelfTalkState.get(getMaid().getId());
            if (state.selfTalkPending) {
                state.selfTalkPendingSinceTick = getMaid().level().getServer().getTickCount();
            }
        };
        if (isOnServerThread()) {
            beat.run();
        } else {
            runOnServerThread(beat);
        }
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // TLM 默认行为：写 assistant 历史（供聊天记录 UI 显示）、显示气泡并给主人发送聊天栏消息
        super.onSuccess(responseChat);
        // 捕获本次写入历史的 assistant 消息：三重校验（队头 + role + 内容）防并发响应线程交错抓取。
        // 父类对空白回复内部转调 onFailure 不写历史，队头为旧消息/null，校验不通过返回 null。
        this.lastAssistantMessage = captureLatestAssistantMessage(responseChat);
        // 登记自话指纹（与历史写入同线程紧邻，供 wrap 区分自话/主人段；
        // 先登记后判空：新老窗口消息都可能随后被 trim，指纹随消息同生同灭）
        SelfTalkProvenance.registerSelfTalk(getMaid(), this.lastAssistantMessage);
        if (this.lastAssistantMessage == null) {
            // 无消息可捕获（空白回复）或校验未过（罕见交错）：
            // 跳过事件/遗忘/广播，复位 pending 防卡死；工具过程同样丢弃（可能留有半截记录）
            runOnServerThread(() -> {
                discardToolHistory();
                SelfTalkState.State state = SelfTalkState.get(getMaid().getId());
                state.selfTalkPending = false;
                state.selfTalkPendingSinceTick = -1;
            });
            return;
        }
        EntityMaid maid = getMaid();
        Runnable finish = () -> {
            // 工具过程从历史里全部丢掉（成对删除，先于遗忘 trim 执行）
            discardToolHistory();
            MinecraftForge.EVENT_BUS.post(new MaidChatReplyEvent(maid, responseChat.getChatText(), welcome));
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

    /**
     * 捕获父类刚写入历史的 assistant 消息（队头=最新，CappedQueue.offerFirst）。
     * 三重校验（队头非空 + role 为 ASSISTANT + 内容与本次响应一致）防同女仆并发响应线程
     * 在写入与捕获间交错时抓取到对方消息；校验不过返回 null。
     */
    private LLMMessage captureLatestAssistantMessage(ResponseChat responseChat) {
        Deque<LLMMessage> deque = getChatManager().getHistory().getDeque();
        LLMMessage head = deque.peekFirst();
        if (head != null && head.role() == Role.ASSISTANT
                && responseChat.toString().equals(head.message())) {
            return head;
        }
        return null;
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        super.onFailure(request, throwable, errorCode);
        runOnServerThread(() -> {
            // 失败链同样丢弃工具过程，否则历史里留下半截工具记录（孤立 tool_calls/tool → 后续 400）
            discardToolHistory();
            SelfTalkState.State state = SelfTalkState.get(getMaid().getId());
            state.selfTalkPending = false;
            state.selfTalkPendingSinceTick = -1;
        });
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
