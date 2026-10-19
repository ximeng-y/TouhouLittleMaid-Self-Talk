package com.maidmod.selftalk.citest.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.maidmod.selftalk.PrebuiltAudioContext;
import com.maidmod.selftalk.SelfTalkCallback;
import com.maidmod.selftalk.SelfTalkRequestToken;
import com.maidmod.selftalk.SelfTalkReuseService;
import com.maidmod.selftalk.citest.CiFaults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 只登记产品实际创建的自话回调，供 server.fault 回放和取消边界断言使用。
 */
@Mixin(SelfTalkCallback.class)
public abstract class CiFaultSelfTalkCallbackMixin {

    @Inject(method = "<init>(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/MaidAIChatManager;"
                    + "Ljava/util/List;ZIDZLcom/maidmod/selftalk/SelfTalkRequestToken;"
                    + "Lcom/maidmod/selftalk/SelfTalkReuseService$ArchiveData;Z"
                    + "Lcom/maidmod/selftalk/PrebuiltAudioContext;)V",
            remap = false, at = @At("TAIL"))
    private void maid_self_talk_ci$capture(
            MaidAIChatManager chatManager,
            List<LLMMessage> messages,
            boolean welcome,
            int keepSelfTalkCount,
            double broadcastRange,
            boolean toolEnabled,
            SelfTalkRequestToken token,
            SelfTalkReuseService.ArchiveData archiveData,
            boolean reusedSource,
            PrebuiltAudioContext prebuiltAudio,
            CallbackInfo ci) {
        CiFaults.captureConstructed((SelfTalkCallback) (Object) this);
    }
}
