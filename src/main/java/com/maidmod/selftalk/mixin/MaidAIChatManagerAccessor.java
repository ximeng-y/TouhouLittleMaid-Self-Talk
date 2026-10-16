package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.CappedQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * 撬开 MaidAIChatManager 的三个私有方法。
 * <p>
 * <ul>
 *   <li>{@code getMessages(MaidAIChatManager, String)} 是组装 [system 设定, 摘要, ...历史]
 *       消息前缀的唯一入口，与玩家 chat 路径完全同构（保证 LLM 提供商上下文前缀缓存一致）。
 *       调用时第一个参数传入实例本身（源码内部调用即为 this.getMessages(this, language)）；</li>
 *   <li>{@code buildMessage(String, EntityMaid, CappedQueue)} 由四栏模式复用：
 *       核心正文经 {@code PapiReplacer} 替换后交给它，摘要与原生历史读取顺序完全不变；</li>
 *   <li>{@code normalChat(String, List, LLMClient)} 由玩家聊天的人设召回会话在规划结束后
 *       恢复调用，使原生代码恰好完成一次原话入历史、一次回调创建与一次正式请求发送。</li>
 * </ul>
 */
@Mixin(MaidAIChatManager.class)
public interface MaidAIChatManagerAccessor {

    @Invoker("getMessages")
    List<LLMMessage> invokeGetMessages(MaidAIChatManager chatManager, String language);

    @Invoker("buildMessage")
    List<LLMMessage> invokeBuildMessage(String setting, EntityMaid maid, CappedQueue<LLMMessage> history);

    @Invoker("normalChat")
    void invokeNormalChat(String message, List<LLMMessage> messages, LLMClient chatClient);
}
