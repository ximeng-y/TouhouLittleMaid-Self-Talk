package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.maidmod.selftalk.SelfTalkTtsTranslationCallback;
import com.maidmod.selftalk.persona.PersonaSplitCallback;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 回包原文保留：TLM 的单参 {@link ResponseChat} 构造器会把模型输出
 * 先 trim 再按 {@code ---} 切分（并把切剩部分里的 {@code ---} 全部删除）。
 * <ul>
 *   <li>「AI 拆分人设」（{@link PersonaSplitCallback}）：人设原文含 {@code ---} 时
 *       拆分输出必然被破坏且无法从切分结果还原，改走不加工的双参构造器；</li>
 *   <li>TTS 翻译（{@link SelfTalkTtsTranslationCallback}）：译文本身不是聊天两段格式，
 *       译文中出现 {@code ---} 会被误切；同样改走双参构造器保留完整译文，
 *       空白输出原样交回调按翻译失败收敛。</li>
 * </ul>
 * 普通聊天的 chat/TTS 分割行为不受影响。
 */
@Mixin(LLMOpenAIClient.class)
public abstract class LLMOpenAIClientMixin {

    @Inject(method = "onTextCall", at = @At("HEAD"), cancellable = true)
    private void selftalk$keepRawContentForSilentCallbacks(ResponseCallback<ResponseChat> callback,
                                                           Message firstChoice, CallbackInfo ci) {
        if (!(callback instanceof PersonaSplitCallback) && !(callback instanceof SelfTalkTtsTranslationCallback)) {
            return;
        }
        // 与宿主 onTextCall 的空白分支保持一致：空白输出原样交给回调，由消费方判失败
        String content = firstChoice == null ? null : firstChoice.getContent();
        if (StringUtils.isBlank(content)) {
            callback.onSuccess(new ResponseChat(StringUtils.EMPTY, StringUtils.EMPTY));
        } else {
            // 双参构造器不 trim、不分割：ttsText 仅占位，两类静默回调只读 getChatText()
            callback.onSuccess(new ResponseChat(content, null));
        }
        ci.cancel();
    }
}
