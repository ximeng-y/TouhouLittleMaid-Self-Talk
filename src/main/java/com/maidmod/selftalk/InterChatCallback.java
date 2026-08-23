package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.UUID;

public class InterChatCallback extends LLMCallback {

    private final EntityMaid peer;
    private final String peerText;
    private final double broadcastRange;
    private final boolean isResponder;
    /** 本条消息在互聊链上的序号（发起者消息为 1），用于链长护栏 */
    private final int chainRound;

    public InterChatCallback(MaidAIChatManager chatManager, List<LLMMessage> messages,
                             EntityMaid peer, String peerText,
                             double broadcastRange, boolean isResponder, int chainRound) {
        super(chatManager, messages);
        this.peer = peer;
        this.peerText = peerText;
        this.broadcastRange = broadcastRange;
        this.isResponder = isResponder;
        this.chainRound = chainRound;
        this.needAddTools = false;
    }

    @Override
    public void onSuccess(ResponseChat responseChat) {
        // 本类不调 super.onSuccess（父类 mixin 的剥离不会进入），首行显式剥离段标签
        SegmentTags.stripResponse(responseChat);
        String chatText = responseChat.getChatText();
        String ttsText = responseChat.getTtsText();
        if (chatText.isBlank() || ttsText.isBlank()) {
            String message = "Error in Response Chat: %s".formatted(responseChat);
            this.onFailure(null, new Throwable(message), com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode.CHAT_TEXT_IS_EMPTY);
            return;
        }
        if (AIConfig.TTS_ENABLED.get() && chatManager.getTTSSite() != null && chatManager.getTTSSite().enabled()) {
            chatManager.tts(chatManager.getTTSSite(), chatText, ttsText, waitingChatBubbleId);
        } else {
            if (chatText != null && !chatText.isBlank() && maid.level() instanceof ServerLevel serverLevel) {
                serverLevel.getServer().submit(() -> maid.getChatBubbleManager().addLLMChatText(chatText, waitingChatBubbleId));
            }
        }
        EntityMaid maid = getMaid();
        Runnable finish = () -> {
            SelfTalkState.State state = SelfTalkState.get(maid.getId());
            state.interChatPending = false;
            state.interChatPendingSinceTick = -1;
            if (isResponder && peerText != null && !peerText.isBlank()) {
                boolean needSync = true;
                if (!state.windowInterChatMsgs.isEmpty()) {
                    String last = state.windowInterChatMsgs.get(state.windowInterChatMsgs.size() - 1).message();
                    if (peerText.equals(last)) needSync = false;
                }
                if (needSync) {
                    MaidInterChatService.syncPeerMessageToWindow(maid, peerText);
                }
            }
            MaidInterChatService.addInterChatMessage(maid, chatText);
            if (peer != null && peer.isAlive() && peer.level() instanceof ServerLevel) {
                MaidInterChatService.syncPeerMessageToWindow(peer, chatText);
            }
            broadcastToNearby(maid, chatText);
            if (peer != null && peer.isAlive() && peer.level() instanceof ServerLevel peerLevel) {
                double prob = Config.INTER_CHAT_CHAIN_PROBABILITY.get();
                if (maid.getRandom().nextDouble() < prob) {
                    tryChain(peer, maid, chatText);
                }
            }
        };
        if (isOnServerThread()) {
            finish.run();
        } else {
            runOnServerThread(finish);
        }
    }

    private void tryChain(EntityMaid nextSpeaker, EntityMaid lastSpeaker, String lastText) {
        if (!(nextSpeaker.level() instanceof ServerLevel level)) return;
        // 热重载下中途关闭互聊时立即终止在途链
        if (!Config.INTER_CHAT_ENABLED.get()) return;
        // 链长护栏：本条消息已是链上第 chainRound 条，达到上限即结束本次互聊，
        // 防止连续概率配到 1.0 等极端配置下无限往返消耗 token
        if (chainRound >= Config.INTER_CHAT_MAX_CHAIN_ROUNDS.get()) return;
        SelfTalkState.State nextState = SelfTalkState.get(nextSpeaker.getId());
        if (nextState.selfTalkPending || nextState.interChatPending || nextState.playerChatCount > 0) return;
        if (!SelfTalkHandler.hasPlayerNearby(nextSpeaker, Config.INTER_CHAT_PLAYER_RANGE.get())) return;
        if (!isMaidNearby(nextSpeaker, lastSpeaker, Config.INTER_CHAT_MAID_RANGE.get())) return;
        if (Config.PLAYER_OPTION_ENABLED.get() && !SelfTalkHandler.isInterChatEnabledForMaid(nextSpeaker, level)) return;
        // 链式续接是发起者回复后的单条连续请求，天然串行、每轮隔一次 LLM 往返，
        // 不走 5~8s 全局节流桶（发起者派发已占用该桶），否则 responder 路径永远被退避。
        MaidInterChatService.triggerResponder(nextSpeaker, lastSpeaker, lastText, broadcastRange, chainRound + 1);
    }

    private boolean isMaidNearby(EntityMaid a, EntityMaid b, double range) {
        if (a.level() != b.level()) return false;
        return a.distanceToSqr(b) <= range * range;
    }

    private void broadcastToNearby(EntityMaid maid, String chatText) {
        if (!(maid.level() instanceof ServerLevel serverLevel)) return;
        // 与 SelfTalkCallback 一致：TLM addLLMChatText 已给主人发过同格式消息，此处跳过主人避免重复
        UUID ownerUuid = maid.getOwnerUUID();
        Component message = Component.literal("<").append(maid.getName()).append("> ").append(chatText).withStyle(ChatFormatting.GRAY);
        AABB box = maid.getBoundingBox().inflate(broadcastRange);
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, box,
                p -> p.isAlive() && !p.isSpectator() && !p.getUUID().equals(ownerUuid))) {
            player.sendSystemMessage(message);
        }
    }

    @Override
    public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
        super.onFailure(request, throwable, errorCode);
        runOnServerThread(() -> {
            SelfTalkState.State state = SelfTalkState.get(getMaid().getId());
            state.interChatPending = false;
            state.interChatPendingSinceTick = -1;
        });
    }
}
