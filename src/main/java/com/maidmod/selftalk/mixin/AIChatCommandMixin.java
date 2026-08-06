package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.command.subcommand.AIChatCommand;
import com.maidmod.selftalk.command.SelfTalkDebugCommand;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 向 TLM 的 {@code /tlm ai_chat} 命令追加 {@code debug test_self_talk} 调试子命令。
 * <p>
 * AIChatCommand.get() 返回的 LiteralArgumentBuilder 是可变的，
 * 在 RETURN 处直接向其追加子命令即可，无需替换返回值。
 */
@Mixin(AIChatCommand.class)
public abstract class AIChatCommandMixin {

    @Inject(method = "get", at = @At("RETURN"))
    private static void maid_self_talk$addDebugSubCommand(
            CallbackInfoReturnable<LiteralArgumentBuilder<CommandSourceStack>> cir) {
        cir.getReturnValue().then(SelfTalkDebugCommand.get());
    }
}
