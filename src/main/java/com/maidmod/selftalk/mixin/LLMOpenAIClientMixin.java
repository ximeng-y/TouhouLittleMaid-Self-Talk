package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.maidmod.selftalk.persona.PersonaSplitCallback;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「AI 拆分人设」回包原文保留：TLM 的单参 {@link ResponseChat} 构造器会把模型输出
 * 先 trim 再按 {@code ---} 切分（并把切剩部分里的 {@code ---} 全部删除），人设原文含
 * {@code ---} 时拆分输出必然被破坏且无法从切分结果还原。这里只对
 * {@link PersonaSplitCallback} 改走不加工的双参构造器，让 {@code getChatText()}
 * 返回完整原文；普通聊天的 chat/TTS 分割行为不受影响。
 */
@Mixin(LLMOpenAIClient.class)
public abstract class LLMOpenAIClientMixin {

    @Inject(method = "onTextCall", at = @At("HEAD"), cancellable = true)
    private void selftalk$keepRawContentForPersonaSplit(ResponseCallback<ResponseChat> callback,
                                                        Message firstChoice, CallbackInfo ci) {
        if (!(callback instanceof PersonaSplitCallback)) {
            return;
        }
        // 与宿主 onTextCall 的空白分支保持一致：空白输出原样交给回调，由解析器判格式错误
        String content = firstChoice == null ? null : firstChoice.getContent();
        if (StringUtils.isBlank(content)) {
            callback.onSuccess(new ResponseChat(StringUtils.EMPTY, StringUtils.EMPTY));
        } else {
            // 双参构造器不 trim、不分割：ttsText 仅占位，拆分回调只读 getChatText()
            callback.onSuccess(new ResponseChat(content, null));
        }
        ci.cancel();
    }
}
