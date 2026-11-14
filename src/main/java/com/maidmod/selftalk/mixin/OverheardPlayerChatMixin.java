package com.maidmod.selftalk.mixin;

import com.maidmod.selftalk.OverheardChatService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 普通玩家聊天：在包监听器的广播调用处建立采集来源作用域。
 * <p>
 * 宿主是 Minecraft 类，外层与 {@code @At} 都走 refmap 重映射（{@code remap = true}），
 * 因此这里写 Mojmap 名称，生产环境由 refmap 映射到 searge 名。
 * <p>
 * 只标记来源、不记录、不分配编号：真正的采集点在 {@code PlayerList} 最终公共广播的 TAIL，
 * 那里才能证明消息确实已经发出。包的收件人是 {@code server.players()}，
 * 与实际发送者同一维度筛选无关，采集时另按发送玩家所在维度取世界。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class OverheardPlayerChatMixin {

    @WrapOperation(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V"))
    private void maid_self_talk$markNormalChatSource(PlayerList playerList, PlayerChatMessage message,
                                                    ServerPlayer sender, ChatType.Bound bound,
                                                    Operation<Void> original) {
        OverheardChatService.ScopeHandle scope = OverheardChatService.beginNormalChat(sender);
        try {
            original.call(playerList, message, sender, bound);
        } finally {
            if (scope != null) {
                scope.close();
            }
        }
    }
}
