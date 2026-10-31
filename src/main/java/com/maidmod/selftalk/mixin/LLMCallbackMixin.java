package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.InterChatCallback;
import com.maidmod.selftalk.MaidSelfTalkService;
import com.maidmod.selftalk.PlayerChatRequest;
import com.maidmod.selftalk.PlayerChatRequestHost;
import com.maidmod.selftalk.SegmentTags;
import com.maidmod.selftalk.SelfTalkCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.http.HttpRequest;

/**
 * 钩 LLMCallback 的生命周期结束（onSuccess/onFailure），解除"玩家 chat 进行中"标记。
 * <p>
 * 仅释放本回调持有的真实玩家登记；未登记的辅助请求不参与计数。
 * 成功转失败或重复结束只接收一次，不依赖回调子类排除名单。
 * <p>
 * 同时承接<b>最终自话回复的历史写入切断</b>：窄范围重定向
 * {@code addAssistantHistory(String)}，只在宿主是 {@link SelfTalkCallback} 时跳过写入。
 * 普通玩家回调原样调用；TLM 的空白回复判定、TTS、气泡、主人聊天栏输出与错误处理全部保留
 * （它们都在被重定向的这一行之后，或由本类其它注入点之外的逻辑负责）。
 */
@Mixin(LLMCallback.class)
public abstract class LLMCallbackMixin implements PlayerChatRequestHost {

    @Unique
    private PlayerChatRequest maid_self_talk$playerChatRequest;

    @Override
    public void maid_self_talk$setPlayerChatRequest(PlayerChatRequest request) {
        maid_self_talk$playerChatRequest = request;
    }

    @Inject(method = "onSuccess", at = @At("HEAD"))
    private void maid_self_talk$onSuccess(ResponseChat responseChat, CallbackInfo ci) {
        // 剥离段标签（先于 TLM 写历史/气泡，标签永不落盘、不上屏）
        SegmentTags.stripResponse(responseChat);
        maid_self_talk$onChatEnd();
    }

    @Inject(method = "onFailure", at = @At("HEAD"))
    private void maid_self_talk$onFailure(HttpRequest request, Throwable throwable, int errorCode, CallbackInfo ci) {
        maid_self_talk$onChatEnd();
    }

    /**
     * 切断最终自话回复的 TLM 历史写入。
     * <p>
     * 宿主是 {@link SelfTalkCallback} 时跳过；{@link InterChatCallback} 不调父类
     * {@code onSuccess}，天然不经过这里。普通玩家回调按原样写入。
     * 自话／欢迎语的独立记录在回调的主线程收尾里直接构造（原 {@code ResponseChat.toString()} 形态），
     * 因此这里跳过不影响模型侧可见内容，只切断 TLM 的待压缩历史。
     */
    @Redirect(method = "onSuccess",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatManager;addAssistantHistory(Ljava/lang/String;)V"))
    private void maid_self_talk$skipSelfTalkHistory(MaidAIChatManager chatManager, String message) {
        if ((Object) this instanceof SelfTalkCallback) {
            return;
        }
        chatManager.addAssistantHistory(message);
    }

    @Unique
    private void maid_self_talk$onChatEnd() {
        PlayerChatRequest request = maid_self_talk$playerChatRequest;
        if (request == null || !request.signalEnd()) {
            return;
        }
        LLMCallback callback = (LLMCallback) (Object) this;
        EntityMaid maid = callback.getMaid();
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        if (callback.isOnServerThread()) {
            MaidSelfTalkService.onPlayerChatEnd(maid, request);
        } else {
            callback.runOnServerThread(() -> MaidSelfTalkService.onPlayerChatEnd(maid, request));
        }
    }
}
