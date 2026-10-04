package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.HistoryRetrievalCache;
import com.maidmod.selftalk.InterChatCallback;
import com.maidmod.selftalk.KeywordPlanCallback;
import com.maidmod.selftalk.MaidSelfTalkService;
import com.maidmod.selftalk.SegmentTags;
import com.maidmod.selftalk.SelfTalkCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.http.HttpRequest;

/**
 * 钩 LLMCallback 的生命周期结束（onSuccess/onFailure），解除"玩家 chat 进行中"标记。
 * <p>
 * 只有 TLM 内部 new 的裸 {@link LLMCallback}（玩家 chat）才参与该计数；
 * 本模组自建的子类回调各自管理状态，此处按类型排除：
 * {@link SelfTalkCallback}、{@link InterChatCallback}（自话／互聊），
 * 以及 {@link KeywordPlanCallback}（检索模式的静默关键词规划——它不是玩家聊天完成，
 * 若误认会让一次规划凭空解除一条在途 player chat 计数）。
 * 回调在 LLM 响应线程执行，状态写入统一调度回服务端主线程。
 */
@Mixin(LLMCallback.class)
public abstract class LLMCallbackMixin {

    @Inject(method = "onSuccess", remap = false, at = @At("HEAD"))
    private void maid_self_talk$onSuccess(ResponseChat responseChat, CallbackInfo ci) {
        // 剥离段标签（先于 TLM 写历史/气泡，标签永不落盘、不上屏）
        SegmentTags.stripResponse(responseChat);
        onChatEnd((LLMCallback) (Object) this);
    }

    @Inject(method = "onFailure", remap = false, at = @At("HEAD"))
    private void maid_self_talk$onFailure(HttpRequest request, Throwable throwable, int errorCode, CallbackInfo ci) {
        onChatEnd((LLMCallback) (Object) this);
    }

    private static void onChatEnd(LLMCallback callback) {
        if (callback instanceof SelfTalkCallback || callback instanceof InterChatCallback
                || callback instanceof KeywordPlanCallback) {
            // 自话/互聊/静默规划回调自行管理各自状态与遗忘，不参与玩家 chat 计数
            return;
        }
        EntityMaid maid = callback.getMaid();
        // 玩家 chat 的最终回复已写入 TLM 历史：玩家的问答可能改变可检索对话，
        // 只做失效标记，下次检索时按来源序列比对决定是否重建
        if (maid != null && !maid.level().isClientSide()) {
            HistoryRetrievalCache.invalidate(maid);
        }
        if (callback.isOnServerThread()) {
            MaidSelfTalkService.onPlayerChatEnd(maid);
        } else {
            callback.runOnServerThread(() -> MaidSelfTalkService.onPlayerChatEnd(maid));
        }
    }
}
