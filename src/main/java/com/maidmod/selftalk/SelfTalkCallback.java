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
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nullable;
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
    /**
     * 预制复用的入库凭据（真实生成链携带）：回复验证通过后据此追加一条入库；
     * 命中复用的投放为 null（复用结果绝不二次入库），携带凭据但中途失败时自然作废。
     */
    private final SelfTalkReuseService.ArchiveData archiveData;
    /** 本回调是否为「复用投放」（只影响入库豁免，展示/广播/档案照常） */
    private final boolean reusedSource;
    /**
     * 预制语音缓存上下文（世界开关开启时携带，否则 null）：复用命中为「命中播放」上下文
     * （携带缓存音频，TTS 入口直接改播缓存），真实生成与复用投放的未命中为「捕获」上下文
     * （条目身份待回填，真实合成的字节经 TTS 回调捕获后按最终身份入库）。
     * 经 {@link SelfTalkTtsGate#runWith} 的重载登记进 TTS 发起区间。
     */
    @Nullable
    private final PrebuiltAudioContext prebuiltAudio;
    /**
     * 翻译失败后的纯文本投放标记：仅作用于本次 {@code super.onSuccess}，
     * 令 TLM 的 getTTSSite() 判空（经 {@code SelfTalkLateDisplayMixin} 的窄重定向），
     * 让缓存的上屏文本走原生文字分支、不发错误语言的 TTS。
     */
    private boolean forceTextOnly;

    public SelfTalkCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                            boolean welcome, int keepSelfTalkCount, double broadcastRange, boolean toolEnabled,
                            SelfTalkRequestToken token) {
        this(chatManager, messages, welcome, keepSelfTalkCount, broadcastRange, toolEnabled, token,
                null, false, null);
    }

    public SelfTalkCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                            boolean welcome, int keepSelfTalkCount, double broadcastRange, boolean toolEnabled,
                            SelfTalkRequestToken token, SelfTalkReuseService.ArchiveData archiveData,
                            boolean reusedSource, @Nullable PrebuiltAudioContext prebuiltAudio) {
        super(chatManager, messages);
        this.welcome = welcome;
        this.keepSelfTalkCount = keepSelfTalkCount;
        this.broadcastRange = broadcastRange;
        this.token = token;
        this.archiveData = archiveData;
        this.reusedSource = reusedSource;
        this.prebuiltAudio = prebuiltAudio;
        // Tool 关闭时模型看不到工具定义；开启时由 TLM 工具循环接管
        this.needAddTools = toolEnabled;
        // 父构造器已创建等待气泡，这里在 welcome 等字段赋值后标记来源（欢迎语不标记，
        // 客户端显示开关只影响自话/互聊的等待气泡）
        if (!welcome) {
            SelfTalkWaitingBubbles.markBubble(this);
        }
    }

    /** 本次请求是否已被清空作废（供工具异步入口在响应线程做失效判定） */
    public boolean isRequestInvalidated() {
        return token != null && !token.isValid();
    }

    /**
     * 本地作废本请求（超时兜底等，服务端主线程调用；幂等）。
     * <p>
     * 执行顺序固定为：先作废本请求自己的令牌（此后迟到成功／失败／已排队上屏任务在最终判定时一律沉默），
     * 再尽力取消上游（可选的生命周期桥，缺失或异常都不得阻断本地清理），
     * 最后按本轮捕获的气泡 id 精准删除本请求的等待气泡。
     * <p>
     * 不推进整只女仆的清空世代、不重建状态条目、不动其它请求的气泡：
     * 超时之后发起的新请求照常工作（pending 字段由调用方在同一次核对里复位）。
     */
    void invalidateLocal() {
        if (token != null) {
            token.invalidate();
        }
        AgentTweaksLifecycleBridge.cancel(this);
        discardWaitingBubble();
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

    /**
     * 成功交付。
     * <p>
     * <b>清空作废判定先于父类的玩家可见副作用</b>：父类 {@code onSuccess} 会提交 TTS 或
     * 气泡／主人聊天栏输出，而清空与响应返回之间存在竞态——若先调父类再判令牌，
     * 清空前的旧内容已经送达玩家，与本次新增的作废语义相违背。
     * 因此这里把「判令牌 + 调父类 + 写档案 + 发事件 + 广播」收进<b>同一个服务端主线程任务</b>，
     * 交付前一步判定；作废时只清理本请求自己的等待气泡与 pending。
     */
    @Override
    public void onSuccess(ResponseChat responseChat) {
        deliverOnServerThread(() -> {
            if (isRequestInvalidated()) {
                // 清空作废：迟到成功不产生任何玩家可见输出（不显示、不播报、不广播），
                // 也不写档案、不进有效上下文、不发事件
                resetSelfTalkPending();
                discardWaitingBubble();
                return;
            }
            // TLM 默认行为：显示气泡并给主人发送聊天栏消息（历史写入已被重定向跳过）、空白回复转 onFailure。
            // 父类在此处只「发起」TTS：远程 TTS 要等一次网络往返、系统 TTS 要等任务被调度，
            // 真正交付都在接下来这段时间之后。用本请求的令牌把这段同步区间登记起来，
            // 让 TLM 在该区间内构造的 TTS 回调也带上清空判定，
            // 否则「回复已返回并开始合成 → 玩家清空 → 合成结果到达」仍会把旧内容送到主人眼前。
            // 音频上下文随令牌一并登记：TTS 入口据此冻结交付身份或改播缓存音频。
            SelfTalkTtsGate.runWith(token, prebuiltAudio, () -> super.onSuccess(responseChat));
            // 父类对空白回复内部转调 onFailure（本类重写已复位 pending 并沉默），此处直接收敛
            if (responseChat.getChatText() == null || responseChat.getChatText().isBlank()) {
                return;
            }
            EntityMaid maid = getMaid();
            // 上下文消息沿用原 ResponseChat.toString()：聊天／TTS 两段内容的既有形式不变，
            // 与玩家 chat 路径写入 TLM 历史的形态一致，模型侧契约无变化
            AutonomousChatRecord record = archiveSelfTalk(maid, responseChat.toString(),
                    responseChat.getChatText());
            // 预制复用入库：真实生成链凭据齐全且开关仍开时追加一条（复用投放 archiveData 为 null，天然豁免）
            maybeAppendPrebuilt(maid, responseChat);
            MinecraftForge.EVENT_BUS.post(new MaidChatReplyEvent(maid, responseChat.getChatText(), welcome));
            MaidSelfTalkService.onSelfTalkFinished(maid, this, record);
            broadcastToNearby(maid, responseChat.getChatText());
        });
    }

    /**
     * 预制复用入库判定（服务端主线程）：凭据存在、非复用投放、令牌有效、
     * 聊天与 TTS 两段都非空白（段标签清洗已在 super.onSuccess 头部完成）、
     * 且当轮有效开关仍开——任一不满足即放弃，绝不写入半个条目。
     * 入库数据用<b>触发时冻结</b>的凭据（指纹/环境表/上屏语言），不读当下的新环境与人设。
     */
    private void maybeAppendPrebuilt(EntityMaid maid, ResponseChat responseChat) {
        if (archiveData == null || reusedSource || isRequestInvalidated()) {
            return;
        }
        if (responseChat.getChatText() == null || responseChat.getChatText().isBlank()
                || responseChat.getTtsText() == null || responseChat.getTtsText().isBlank()) {
            return;
        }
        if (!PlayerSettingsStore.isPrebuiltReuseEnabledForMaid(maid.level().getServer(), maid)) {
            return;
        }
        SelfTalkReuseStore.PrebuiltEntry accepted = SelfTalkReuseService.appendEntry(maid.level().getServer(),
                archiveData, responseChat.getChatText(), responseChat.getTtsText(), chatManager.getTTSLanguage());
        if (prebuiltAudio != null) {
            // 音频缓存结算：入库被拒（accepted 为 null）时上下文直接作废，绝不登记孤立音频；
            // 接收成功则按最终条目身份结算（此前的合成字节捕获与身份冻结均已就位时）
            prebuiltAudio.onEntryAccepted(accepted);
            prebuiltAudio.trySettle(maid.level().getServer());
        }
    }

    /**
     * 复用投放入口（服务端主线程调用）：命中旧稿后走与真实回复完全一致的交付链
     * （气泡、主人聊天栏、独立档案、有效上下文、回复事件、遗忘检查、附近广播、原生 TTS），
     * 区别仅是跳过入库（archiveData 为 null）且不产生任何 LLM 请求。
     */
    public void deliverReused(ResponseChat responseChat) {
        forceTextOnly = false;
        onSuccess(responseChat);
    }

    /**
     * 翻译失败后的纯文本投放：缓存的上屏文本照常显示，但强制 TLM 走原生文字分支，
     * 绝不发出错误语言的 TTS；不改库、不回退全量生成。
     * ResponseChat 仍携带非空白的缓存 TTS 段（仅供上游空白校验），真正送合成前已被重定向拦下。
     */
    public void deliverReusedTextOnly(ResponseChat responseChat) {
        forceTextOnly = true;
        onSuccess(responseChat);
    }

    /** 复用投放在交付前的静默作废（翻译会话被清空等取消时调用）：只清本请求的气泡与 pending */
    public void discardReusedDelivery() {
        discardWaitingBubble();
        resetSelfTalkPending();
    }

    /** 是否为纯文本投放（供 {@code SelfTalkLateDisplayMixin} 的 getTTSSite 重定向读取） */
    public boolean forceTextOnly() {
        return forceTextOnly;
    }

    /**
     * 把交付动作放到服务端主线程执行（已在主线程则直接执行）。
     * <p>
     * LLM 回调运行在响应线程，而令牌判定必须与玩家可见副作用在同一任务内原子完成。
     */
    private void deliverOnServerThread(Runnable action) {
        if (isOnServerThread()) {
            action.run();
        } else {
            runOnServerThread(action);
        }
    }

    /** 清理本请求自己的等待气泡（按本轮捕获的 id 精准删除，绝不误删新请求的气泡）；包级私有供同步派发失败出口复用 */
    void discardWaitingBubble() {
        if (waitingChatBubbleId == 0) {
            return;
        }
        EntityMaid maid = getMaid();
        if (maid != null && maid.isAlive()) {
            maid.getChatBubbleManager().removeChatBubble(waitingChatBubbleId);
        }
    }

    /**
     * <b>未开启 TTS</b>时，TLM 那条延迟文字上屏任务的执行当下是否仍允许交付
     * （供 {@code SelfTalkLateDisplayMixin} 在 {@code lambda$onSuccess$1} 头部调用）。
     * <p>
     * 外层交付判定与这条被投递的任务之间仍可能插进一次清空，因此上屏前再判一次：
     * 作废则只回收本条自己的等待气泡，不写气泡、不给主人发聊天栏消息。
     * 此时请求收尾（pending、档案、事件）早已完成，这里只补掉那一次可见输出。
     */
    public boolean allowLateDisplay() {
        if (!isRequestInvalidated()) {
            return true;
        }
        discardWaitingBubble();
        return false;
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

    /**
     * 失败交付。
     * <p>
     * <b>不调父类 {@code onFailure}</b>：父类会重新提交一个服务端任务，无条件向主人发送红色错误文本
     * 并移除等待气泡——对已清空作废的迟到请求而言，这就是「清空前的旧请求仍在产生可见输出」。
     * 这里改为在当前任务内先判许可：作废则只清本请求的等待气泡与 pending，绝不显示任何文本；
     * 未作废则复用 TLM 的错误文本生成逻辑（红色错误 + 收起等待气泡）后收敛。
     */
    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        deliverOnServerThread(() -> {
            if (isRequestInvalidated()) {
                // 清空作废：迟到失败不显示任何错误文本，只清本请求自己的资源
                resetSelfTalkPending();
                discardWaitingBubble();
                return;
            }
            EntityMaid maid = getMaid();
            if (maid.getOwner() instanceof ServerPlayer player) {
                // 与 TLM 父类相同的文本生成（ServiceType.LLM + 错误码 + 本地化原因），只是不再另投递任务
                String cause = throwable == null ? null : throwable.getLocalizedMessage();
                var errorMessage = com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode.getErrorMessage(
                        com.github.tartaricacid.touhoulittlemaid.ai.service.ServiceType.LLM, errorCode, cause);
                player.sendSystemMessage(errorMessage.withStyle(ChatFormatting.RED));
            }
            discardWaitingBubble();
            resetSelfTalkPending();
        });
    }

    /**
     * 复位本请求的自话 pending（服务端主线程）：只清仍指向本回调的条目，
     * 清空作废的旧回调不得经 {@code get()} 重建状态条目，
     * 也不得清掉清空之后新请求的 pending。包级私有：复用投放在交付前的静默作废同样收尾于此。
     */
    void resetSelfTalkPending() {
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
        broadcastChatToNearby(maid, chatText, broadcastRange);
    }

    /**
     * 附近玩家广播（自话／互聊共用）：向 maid 周围 range 格内存活、非旁观、非主人的玩家
     * 发送灰色「{@code <女仆名> 内容}」系统消息，与 TLM {@code addLLMChatText} 相同格式。
     * 只在服务端主线程调用。
     */
    static void broadcastChatToNearby(EntityMaid maid, String chatText, double range) {
        if (!(maid.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        UUID ownerUuid = maid.getOwnerUUID();
        Component message = Component.literal("<")
                .append(maid.getName())
                .append("> ")
                .append(chatText)
                .withStyle(ChatFormatting.GRAY);
        AABB box = maid.getBoundingBox().inflate(range);
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
