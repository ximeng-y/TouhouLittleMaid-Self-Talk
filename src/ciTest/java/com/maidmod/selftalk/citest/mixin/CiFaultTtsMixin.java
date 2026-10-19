package com.maidmod.selftalk.citest.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.citest.CiFaults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 仅在 CI 明确 arm 后让真实 TTS 发起入口同步抛错。
 */
@Mixin(MaidAIChatManager.class)
public abstract class CiFaultTtsMixin {

    @Inject(method = "tts", remap = false, at = @At("HEAD"))
    private void maid_self_talk_ci$throwSynchronousTts(
            TTSSite site,
            String chatText,
            String ttsText,
            long waitingChatBubbleId,
            CallbackInfo ci) {
        EntityMaid maid = ((MaidAIChatManager) (Object) this).getMaid();
        if (CiFaults.consumeSynchronousTtsThrow(maid)) {
            throw new IllegalStateException("CI synchronous TTS failure");
        }
    }
}
