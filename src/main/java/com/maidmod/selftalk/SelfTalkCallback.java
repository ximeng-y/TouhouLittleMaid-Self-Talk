package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.neoforged.neoforge.common.NeoForge;

import java.net.http.HttpRequest;
import java.util.List;

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

    public SelfTalkCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                            boolean welcome, int keepSelfTalkCount) {
        super(chatManager, messages);
        this.welcome = welcome;
        this.keepSelfTalkCount = keepSelfTalkCount;
        // 模型看不到工具定义，天然不会调用任何 tool
        this.needAddTools = false;
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // TLM 默认行为：写 assistant 历史（供聊天记录 UI 显示）、显示气泡/TTS
        super.onSuccess(responseChat);
        EntityMaid maid = getMaid();
        Runnable finish = () -> {
            NeoForge.EVENT_BUS.post(new MaidChatReplyEvent(maid, responseChat.getChatText(), welcome));
            MaidSelfTalkService.onSelfTalkFinished(maid, this);
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

    public boolean isWelcome() {
        return welcome;
    }

    public int getKeepSelfTalkCount() {
        return keepSelfTalkCount;
    }
}
