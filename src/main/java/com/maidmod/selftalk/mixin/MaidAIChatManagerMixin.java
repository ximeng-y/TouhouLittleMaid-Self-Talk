package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.maidmod.selftalk.MaidInterChatService;
import com.maidmod.selftalk.MaidSelfTalkService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 钩玩家 chat 真实派发入口（MaidAIChatManager.normalChat，private 方法、服务端主线程调用）。
 * <p>
 * 作用：
 * <ul>
 *   <li>HEAD：把互聊窗口内容拼接进本次请求的 messages（此刻 TLM 刚构建完 [system 设定, 摘要, ...历史]，
 *       尚未 append 玩家新消息），使玩家 chat 上下文 = 对话历史（含自话）+ 互聊记录 + 玩家的话；</li>
 *   <li>TAIL：标记该女仆"玩家 chat 进行中"（计数），自话/互聊触发时若命中则跳过（防交错写历史）；
 *       清空自话/互聊计数窗口（打断连续、计数重新开始。自话记录已写入 TLM 历史、
 *       互聊记录已随 HEAD 注入本次请求，清空不丢已注入内容）。</li>
 * </ul>
 * TAIL 注入点选在请求已提交之后：TLM {@code chat()} 有大量提前返回路径
 * （LLM 关闭/token 超限/site 缺失/密钥缺失/无人设/历史压缩）不产生任何回调，
 * 若在 chat 入口标记则标记可能永不清除、该女仆自话永久停摆；
 * {@code normalChat} 是唯一创建玩家 chat 回调（LLMCallback）的路径，
 * 且 client.chat 同步抛异常时 TAIL 同样不会执行——标记与回调生命周期严格绑定。
 * HEAD 注入只向 messages 尾部追加互聊 assistant 消息（不破坏 tool 配对），
 * 且同步发生于 client.chat 之前，异步请求线程必然读到注入后的列表。
 */
@Mixin(MaidAIChatManager.class)
public abstract class MaidAIChatManagerMixin {

    @Inject(method = "normalChat", at = @At("HEAD"))
    private void maid_self_talk$injectInterChatContext(String message, List<LLMMessage> messages,
                                                       LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        MaidInterChatService.injectPlayerChatContext(self.getMaid(), messages);
    }

    @Inject(method = "normalChat", at = @At("TAIL"))
    private void maid_self_talk$onPlayerChatStart(String message, List<LLMMessage> messages,
                                                  LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        MaidSelfTalkService.onPlayerChatStart(self.getMaid());
    }
}
