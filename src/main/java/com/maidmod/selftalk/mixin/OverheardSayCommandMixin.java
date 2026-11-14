package com.maidmod.selftalk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.maidmod.selftalk.OverheardChatService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

/**
 * 玩家本人执行的 {@code /say}：在 {@code PlayerList} 的「命令来源」广播重载处建立采集来源作用域。
 * <p>
 * 该重载只有 {@code SayCommand} 使用，传入的正是已解析的命令来源与绑定聊天类型，
 * 因此不需要注入 {@code SayCommand} 的合成 lambda（其生产名不参与 Forge 的 mcp→srg 映射，
 * 编译期无法同时满足开发名与生产名）。宿主是 Minecraft 类，外层与 {@code @At} 都走 refmap 重映射。
 * <p>
 * 合格条件严格按「本人执行」判定：{@code source.source} 是真实 {@code ServerPlayer}、
 * {@code source.getEntity()} 就是该玩家、不是平台伪装玩家，且绑定类型为 {@code SAY_COMMAND}。
 * 不能只判断 {@code getPlayer()}，否则控制台／命令方块 {@code execute as 玩家 run say}
 * 会被当成本人发言。两种情况都执行原广播一次，不改变命令行为。
 */
@Mixin(PlayerList.class)
public abstract class OverheardSayCommandMixin {

    @WrapOperation(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V"))
    private void maid_self_talk$markPlayerSaySource(PlayerList playerList, PlayerChatMessage message,
                                                    CommandSourceStack source, ChatType.Bound bound,
                                                    Operation<Void> original) {
        ServerPlayer sender = realSender(source, bound);
        OverheardChatService.ScopeHandle scope = sender == null ? null : OverheardChatService.beginPlayerSay(sender);
        try {
            original.call(playerList, message, (Predicate<ServerPlayer>) source::shouldFilterMessageTo,
                    source.getPlayer(), bound);
        } finally {
            if (scope != null) {
                scope.close();
            }
        }
    }

    /** 命令来源是否确为玩家本人执行；控制台、命令方块、伪装玩家与非 SAY 类型一律不合格 */
    private static ServerPlayer realSender(CommandSourceStack source, ChatType.Bound bound) {
        if (source == null || bound == null || !(source.source instanceof ServerPlayer player)) {
            return null;
        }
        if (source.getEntity() != player) {
            return null;
        }
        if (player instanceof FakePlayer) {
            return null;
        }
        return player;
    }
}
