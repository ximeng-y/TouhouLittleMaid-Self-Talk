package com.maidmod.selftalk.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.maidmod.selftalk.OverheardChatService;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * 唯一采集宿主：所有广播重载都汇聚到 {@code PlayerList} 的私有四参数方法，
 * 这里 TAIL 注入一次，代表「消息确实已经发给所有收件人」。
 * <p>
 * 本次实际广播对象用 {@code @Local} 按类型捕获（该方法只有一个 {@code OutgoingChatMessage}
 * 局部变量），正文取其 {@code content().getString()}——这正是服务端共同广播的纯文本，
 * 不取命令原串、不取签名内容、不重新装饰（玩家 /say 的选择器已在上游展开）。
 * <p>
 * 不在此处按收件人循环记录：每个收件人只显示，消息本身只采集一次。
 */
@Mixin(PlayerList.class)
public abstract class OverheardPlayerListMixin {

    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("TAIL"))
    private void maid_self_talk$recordOverheardChat(PlayerChatMessage message, Predicate<ServerPlayer> filter,
                                                    @Nullable ServerPlayer sender, ChatType.Bound bound,
                                                    CallbackInfo ci, @Local OutgoingChatMessage outgoing) {
        if (outgoing == null) {
            return;
        }
        OverheardChatService.onBroadcastCompleted(outgoing.content().getString());
    }
}
