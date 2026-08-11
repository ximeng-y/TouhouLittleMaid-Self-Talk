package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidSelfTalkService;
import com.maidmod.selftalk.SelfTalkCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.http.HttpRequest;

/**
 * 钩 LLMCallback 的生命周期结束（onSuccess/onFailure），解除"玩家 chat 进行中"标记。
 * <p>
 * 玩家 chat 的 callback 是 TLM 内部 new 的裸 {@link LLMCallback}；
 * 本模组的自话使用 {@link SelfTalkCallback} 子类自行管理状态，此处通过 instanceof 跳过。
 * 回调在 LLM 响应线程执行，状态写入统一调度回服务端主线程。
 */
@Mixin(LLMCallback.class)
public abstract class LLMCallbackMixin {

    @Inject(method = "onSuccess", remap = false, at = @At("HEAD"))
    private void maid_self_talk$onSuccess(ResponseChat responseChat, CallbackInfo ci) {
        onChatEnd((LLMCallback) (Object) this);
    }

    @Inject(method = "onFailure", remap = false, at = @At("HEAD"))
    private void maid_self_talk$onFailure(HttpRequest request, Throwable throwable, int errorCode, CallbackInfo ci) {
        onChatEnd((LLMCallback) (Object) this);
    }

    private static void onChatEnd(LLMCallback callback) {
        if (callback instanceof SelfTalkCallback) {
            // 自话回调自行管理状态与遗忘
            return;
        }
        EntityMaid maid = callback.getMaid();
        if (callback.isOnServerThread()) {
            MaidSelfTalkService.onPlayerChatEnd(maid);
        } else {
            callback.runOnServerThread(() -> MaidSelfTalkService.onPlayerChatEnd(maid));
        }
    }
}
