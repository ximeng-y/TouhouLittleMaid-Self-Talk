package com.maidmod.selftalk.command;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.MaidSelfTalkService;
import com.maidmod.selftalk.SelfTalkState;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Comparator;
import java.util.List;

/**
 * 本模组独立主指令：{@code /tlm_ai_pro debug test_self_talk [<maid>]}。
 * <p>
 * 不注入 TLM 命令树，使用标准 {@link RegisterCommandsEvent} 注册，与 TLM 完全解耦。
 * 权限要求与 /tlm 一致（op，permission 2）。
 * <p>
 * test_self_talk：快速触发一次女仆自言自语，用于测试（绕过冷却/态判定/半径等触发条件，
 * 但保留 AI 硬性前置：LLM 开关、站点可用、有人设、无进行中对话）。
 */
public final class SelfTalkDebugCommand {

    private static final String ROOT_NAME = "tlm_ai_pro";
    private static final String DEBUG_NAME = "debug";
    private static final String TEST_SELF_TALK_NAME = "test_self_talk";
    private static final String MAID_ARG = "maid";
    /** 无参数时，从命令执行者附近多少格内寻找最近女仆 */
    private static final double AUTO_FIND_RANGE = 32.0;

    private SelfTalkDebugCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT_NAME)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal(DEBUG_NAME)
                        .then(Commands.literal(TEST_SELF_TALK_NAME)
                                // 无参数：自动寻找执行者附近最近的女仆
                                .executes(SelfTalkDebugCommand::testSelfTalk)
                                // 指定女仆
                                .then(Commands.argument(MAID_ARG, EntityArgument.entity())
                                        .executes(SelfTalkDebugCommand::testSelfTalk)))));
        MaidSelfTalkMod.LOGGER.info("Registered /{} {} {}", ROOT_NAME, DEBUG_NAME, TEST_SELF_TALK_NAME);
    }

    private static int testSelfTalk(CommandContext<CommandSourceStack> context) {
        EntityMaid maid = resolveMaid(context);
        if (maid == null) {
            context.getSource().sendFailure(Component.translatable(
                    "commands.maid_self_talk.test_self_talk.no_maid"));
            return 0;
        }
        // 有进行中的对话时拒绝（与自话触发逻辑一致，防止交错写历史）
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        if (state.selfTalkPending || state.playerChatPending) {
            context.getSource().sendFailure(Component.translatable(
                    "commands.maid_self_talk.test_self_talk.busy"));
            return 0;
        }
        boolean triggered = MaidSelfTalkService.triggerSelfTalk(maid, false,
                Config.STATE1_KEEP_SELF_TALK_COUNT.get(), Config.STATE1_PLAYER_RANGE.get());
        if (triggered) {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "commands.maid_self_talk.test_self_talk.triggered", maid.getDisplayName()), true);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendFailure(Component.translatable(
                "commands.maid_self_talk.test_self_talk.failed"));
        return 0;
    }

    /** 解析目标女仆：指定参数优先；无参数时取执行者附近最近的女仆 */
    private static EntityMaid resolveMaid(CommandContext<CommandSourceStack> context) {
        try {
            Entity entity = EntityArgument.getEntity(context, MAID_ARG);
            return entity instanceof EntityMaid maid ? maid : null;
        } catch (CommandSyntaxException e) {
            Entity source = context.getSource().getEntity();
            if (source != null && source.level() instanceof ServerLevel level) {
                List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class,
                        source.getBoundingBox().inflate(AUTO_FIND_RANGE));
                return maids.stream()
                        .min(Comparator.comparingDouble(m -> m.distanceToSqr(source)))
                        .orElse(null);
            }
            return null;
        }
    }
}
