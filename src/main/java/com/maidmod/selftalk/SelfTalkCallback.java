package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
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
 * 自言自语／欢迎语专用的 LLM 回调。
 * <p>
 * 与玩家 chat 的裸 {@link LLMCallback} 的区别：
 * <ul>
 *   <li>{@code needAddTools} 由 Tool 开关判定决定（默认 false）：关闭时模型看不到任何工具定义，
 *       天然不会调用 tool；开启时 TLM 原生工具循环接管（中间轮不上屏，仅思考气泡副文本），
 *       最终 onSuccess 的回答才走本 mod 的广播；</li>
 *   <li>{@code shouldCacheTokenUsage} 保持默认 {@code true}：自话会更新女仆的 lastChatTokenUsage，
 *       从而占用上下文压缩额度——但压缩只在玩家 chat 时触发（tryCompressBeforeChat 仅由 chat() 调用），
 *       因此自话即使撑大上下文也不会立即触发压缩；</li>
 *   <li><b>历史归属</b>：本轮回合与工具过程都不写入 TLM 历史（由 {@code LLMCallbackMixin} 与
 *       {@code InterChatToolLifecycleMixin} 的窄范围重定向跳过写入），有效回复在服务端主线程
 *       直接构造独立记录进档案；TLM 的空白判定、TTS、气泡、主人聊天栏输出与错误处理全部保留；</li>
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
    /**
     * 本次请求的清空世代令牌：手动清空后失效——迟到结果不写档案、不进有效上下文、
     * 不发事件、不广播，只清自己的资源；pending 复位也不得误清新请求的状态。
     */
    private final SelfTalkRequestToken token;

    public SelfTalkCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                            boolean welcome, int keepSelfTalkCount, double broadcastRange, boolean toolEnabled,
                            SelfTalkRequestToken token) {
        super(chatManager, messages);
        this.welcome = welcome;
        this.keepSelfTalkCount = keepSelfTalkCount;
        this.broadcastRange = broadcastRange;
        this.token = token;
        // Tool 关闭时模型看不到工具定义；开启时由 TLM 工具循环接管
        this.needAddTools = toolEnabled;
    }

    /** 本次请求是否已被清空作废（供工具异步入口在响应线程做失效判定） */
    public boolean isRequestInvalidated() {
        return token != null && !token.isValid();
    }

    /**
     * 工具轮次：不再捕获历史队头——本轮回合与工具过程都不写入 TLM 历史，
     * 由 {@code InterChatToolLifecycleMixin} 的重定向在写入点跳过。
     */
    @Override
    public void onFunctionCall(Message choice, LLMClient client) {
        super.onFunctionCall(choice, client);
    }

    /**
     * 工具结果：父类照常把结果并入本轮请求；这里只刷新 pending 心跳
     * （语义从「请求发起后 5 分钟超时」变为「最后一次工具活动后 5 分钟超时」，
     * 防止 16 轮工具链被 SelfTalkHandler 的超时兜底误判空闲而并发派发第二个请求）。
     */
    @Override
    public LLMCallback addToolResult(String result, String toolId) {
        LLMCallback cb = super.addToolResult(result, toolId);
        refreshPendingHeartbeat();
        return cb;
    }

    /** 工具轮次心跳：刷新 pending 起始 tick（须在服务端主线程写状态） */
    private void refreshPendingHeartbeat() {
        Runnable beat = () -> {
            SelfTalkState.State state = SelfTalkState.peek(getMaid().getId());
            if (state != null && state.selfTalkPending && isRequestOwner(state)) {
                state.selfTalkPendingSinceTick = getMaid().level().getServer().getTickCount();
            }
        };
        if (isOnServerThread()) {
            beat.run();
        } else {
            runOnServerThread(beat);
        }
    }

    /**
     * 本回调是否仍是状态表上「当前」的自话请求。
     * <p>
     * 用不创建状态的查询并核对请求身份：被清空作废的旧回调不得经 {@code get()} 重建状态条目，
     * 也不得刷新清空之后新请求的心跳。
     */
    private boolean isRequestOwner(SelfTalkState.State state) {
        return state.currentSelfTalkCallback == this;
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // TLM 默认行为：显示气泡并给主人发送聊天栏消息（历史写入已被重定向跳过）、空白回复转 onFailure
        super.onSuccess(responseChat);
        // 父类对空白回复内部转调 onFailure（本类重写已复位 pending 并沉默），此处直接收敛
        if (responseChat.getChatText() == null || responseChat.getChatText().isBlank()) {
            return;
        }
        EntityMaid maid = getMaid();
        // 上下文消息沿用原 ResponseChat.toString()：聊天／TTS 两段内容的既有形式不变，
        // 与玩家 chat 路径写入 TLM 历史的形态一致，模型侧契约无变化
        String contextMessage = responseChat.toString();
        String chatText = responseChat.getChatText();
        Runnable finish = () -> {
            if (isRequestInvalidated()) {
                // 清空作废：迟到成功不写档案、不进有效上下文、不发事件、不广播，只复位自己的 pending
                resetSelfTalkPending();
                return;
            }
            AutonomousChatRecord record = archiveSelfTalk(maid, contextMessage, chatText);
            NeoForge.EVENT_BUS.post(new MaidChatReplyEvent(maid, chatText, welcome));
            MaidSelfTalkService.onSelfTalkFinished(maid, this, record);
            broadcastToNearby(maid, chatText);
        };
        if (isOnServerThread()) {
            finish.run();
        } else {
            // LLM 回调在响应线程，档案写入、状态与事件必须回到服务端主线程
            runOnServerThread(finish);
        }
    }

    /** 在服务端主线程把本次回复写成独立记录（展示档案 + 有效自话上下文），返回该记录 */
    private AutonomousChatRecord archiveSelfTalk(EntityMaid maid, String contextMessage, String chatText) {
        AutonomousChatHistory archive = AutonomousChatHistoryHost.of(maid);
        if (archive == null) {
            return null;
        }
        return archive.append(welcome ? AutonomousChatRecord.Source.WELCOME
                        : AutonomousChatRecord.Source.SELF_TALK,
                maid.getUUID(), AutonomousChatHistoryHost.displayNameOf(maid),
                maid.level().getGameTime(), contextMessage, chatText);
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        super.onFailure(request, throwable, errorCode);
        runOnServerThread(this::resetSelfTalkPending);
    }

    /**
     * 复位本请求的自话 pending（服务端主线程）：只清仍指向本回调的条目，
     * 清空作废的旧回调不得清掉清空之后新请求的 pending。
     */
    private void resetSelfTalkPending() {
        SelfTalkState.State state = SelfTalkState.peek(getMaid().getId());
        if (state == null || !isRequestOwner(state)) {
            return;
        }
        state.selfTalkPending = false;
        state.selfTalkPendingSinceTick = -1;
        state.currentSelfTalkCallback = null;
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
}
