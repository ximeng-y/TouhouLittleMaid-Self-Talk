package com.maidmod.selftalk;

/** 由实际 LLMCallback 持有本轮玩家请求登记，不替换原生回调类型。 */
public interface PlayerChatRequestHost {
    void maid_self_talk$setPlayerChatRequest(PlayerChatRequest request);
}
