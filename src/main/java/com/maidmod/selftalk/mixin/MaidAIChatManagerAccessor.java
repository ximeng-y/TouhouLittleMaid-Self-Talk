package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * 撬开 MaidAIChatManager.getMessages（private）。
 * <p>
 * getMessages(MaidAIChatManager, String) 是组装 [system 设定, 摘要, ...历史] 消息前缀的唯一入口，
 * 与玩家 chat 路径完全同构（保证 LLM 提供商上下文前缀缓存一致）。
 * 调用时第一个参数传入实例本身（源码内部调用即为 this.getMessages(this, language)）。
 */
@Mixin(MaidAIChatManager.class)
public interface MaidAIChatManagerAccessor {

    @Invoker("getMessages")
    List<LLMMessage> invokeGetMessages(MaidAIChatManager chatManager, String language);
}
