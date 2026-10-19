package com.maidmod.selftalk.citest.mixin;

import com.github.tartaricacid.touhoulittlemaid.network.message.ai.TTSAudioToClientPackage;
import com.maidmod.selftalk.citest.CiClientProbe;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TTS 下发观测：记录真实音频包的长度与文件头，用于断言合成／缓存链路确实到达客户端 */
@Mixin(TTSAudioToClientPackage.class)
public abstract class CiTtsAudioMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private static void maid_self_talk_ci$captureTts(TTSAudioToClientPackage message, IPayloadContext context,
                                                     CallbackInfo ci) {
        CiClientProbe.tts(message.maidId(), message.data());
    }
}
