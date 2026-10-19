package com.maidmod.selftalk.citest;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.FunctionToolCall;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.WaitingChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.item.ItemFilm;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.maidmod.selftalk.AutonomousChatHistory;
import com.maidmod.selftalk.AutonomousChatHistoryHost;
import com.maidmod.selftalk.AutonomousChatRecord;
import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.MaidPersonaMode;
import com.maidmod.selftalk.SelfTalkReuseService;
import com.maidmod.selftalk.SelfTalkReuseStore;
import com.maidmod.selftalk.SelfTalkState;
import com.maidmod.selftalk.SelfTalkWaitingBubbleData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端测试驱动。
 * <p>
 * 只做三件事：按动作准备真实服务端场景（生成／移动女仆、真实伤害／死亡／复活、改配置）、
 * 执行真实命令与读取真实状态。自话／互聊／欢迎语均由产品自己的 tick 状态机触发，
 * 这里不调用任何产品入口方法，也不伪造 LLM 回调。
 */
public final class CiServerDriver {

    private static int lastSpawnId = -1;

    private CiServerDriver() {
    }

    public static JsonObject execute(String op, JsonObject action, MinecraftServer server) {
        return switch (op) {
            case "server.ping" -> ping(server);
            case "server.setup" -> setup(action, server);
            case "server.apply_config" -> applyConfig(action);
            case "server.player" -> player(action, server);
            case "server.spawn_maid" -> spawnMaid(action, server);
            case "server.update_maid" -> updateMaid(action, server);
            case "server.command" -> command(action, server);
            case "server.damage" -> damage(action, server);
            case "server.kill" -> kill(action, server);
            case "server.spawn_animal" -> spawnAnimal(action, server);
            case "server.revive" -> revive(action, server);
            case "server.remove_maid" -> removeMaid(action, server);
            case "server.query" -> query(server);
            case "server.settings" -> settings(action, server);
            case "server.save" -> save(server);
            case "server.stop" -> stop(server);
            default -> throw new IllegalArgumentException("unknown server op: " + op);
        };
    }

    private static JsonObject ping(MinecraftServer server) {
        JsonObject out = new JsonObject();
        out.addProperty("tick", server.getTickCount());
        out.addProperty("players", server.getPlayerList().getPlayerCount());
        out.addProperty("running", !server.isStopped());
        return out;
    }

    private static JsonObject setup(JsonObject action, MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
        level.getGameRules().getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_SENDCOMMANDFEEDBACK).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        level.setDayTime(6000L);
        level.setWeatherParameters(6000, 0, false, false);
        server.setDifficulty(Difficulty.NORMAL, true);
        double x = CiJson.number(action, "spawnX", 0.5);
        double z = CiJson.number(action, "spawnZ", 0.5);
        level.setDefaultSpawnPos(BlockPos.containing(x, 64, z), 0.0F);
        applyConfig(action);
        JsonObject out = new JsonObject();
        out.addProperty("tick", server.getTickCount());
        out.addProperty("difficulty", server.getWorldData().getDifficulty().name());
        return out;
    }

    private static JsonObject applyConfig(JsonObject action) {
        JsonObject values = CiJson.obj(action, "values");
        values.entrySet().forEach(entry -> applyConfigValue(entry.getKey(), entry.getValue()));
        JsonObject out = new JsonObject();
        out.addProperty("enabled", Config.ENABLED.get());
        out.addProperty("state1Min", Config.STATE1_MIN_INTERVAL.get());
        out.addProperty("interChatEnabled", Config.INTER_CHAT_ENABLED.get());
        return out;
    }

    private static void applyConfigValue(String key, com.google.gson.JsonElement value) {
        boolean bool = value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : Boolean.parseBoolean(value.getAsString());
        // 字符串配置（语言、枚举）不能走数值转换；只有数字原始值才取整数/浮点。
        boolean numeric = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
        int integer = numeric ? value.getAsInt() : 0;
        double number = numeric ? value.getAsDouble() : 0;
        switch (key) {
            case "enabled" -> Config.ENABLED.set(bool);
            case "playerOptionEnabled" -> Config.PLAYER_OPTION_ENABLED.set(bool);
            case "toolCallEnabled" -> Config.TOOL_CALL_ENABLED.set(bool);
            case "state1Enabled" -> Config.STATE1_ENABLED.set(bool);
            case "state1Min" -> Config.STATE1_MIN_INTERVAL.set(integer);
            case "state1Max" -> Config.STATE1_MAX_INTERVAL.set(integer);
            case "state1Range" -> Config.STATE1_PLAYER_RANGE.set(number);
            case "state1Keep" -> Config.STATE1_KEEP_SELF_TALK_COUNT.set(integer);
            case "state2Enabled" -> Config.STATE2_ENABLED.set(bool);
            case "state2Min" -> Config.STATE2_MIN_INTERVAL.set(integer);
            case "state2Max" -> Config.STATE2_MAX_INTERVAL.set(integer);
            case "state2Range" -> Config.STATE2_PLAYER_RANGE.set(number);
            case "state2Keep" -> Config.STATE2_KEEP_SELF_TALK_COUNT.set(integer);
            case "welcomeEnabled" -> Config.WELCOME_ENABLED.set(bool);
            case "welcomeWindow" -> Config.WELCOME_WINDOW_TICKS.set(integer);
            case "maxTriggerPerSecond" -> Config.MAX_TRIGGER_PER_SECOND.set(integer);
            case "selfTalkMinInterval" -> Config.SELF_TALK_MIN_INTERVAL.set(integer);
            case "selfTalkMaxInterval" -> Config.SELF_TALK_MAX_INTERVAL.set(integer);
            case "deferQueueMax" -> Config.DEFER_QUEUE_MAX.set(integer);
            case "selfTalkLanguage" -> Config.SELF_TALK_LANGUAGE.set(value.getAsString());
            case "interChatEnabled" -> Config.INTER_CHAT_ENABLED.set(bool);
            case "interChatMin" -> Config.INTER_CHAT_MIN_INTERVAL.set(integer);
            case "interChatMax" -> Config.INTER_CHAT_MAX_INTERVAL.set(integer);
            case "interChatPlayerRange" -> Config.INTER_CHAT_PLAYER_RANGE.set(number);
            case "interChatMaidRange" -> Config.INTER_CHAT_MAID_RANGE.set(number);
            case "interChatKeepRounds" -> Config.INTER_CHAT_KEEP_ROUNDS.set(integer);
            case "interChatChainProbability" -> Config.INTER_CHAT_CHAIN_PROBABILITY.set(number);
            case "interChatMaxChainRounds" -> Config.INTER_CHAT_MAX_CHAIN_ROUNDS.set(integer);
            case "interChatPairLockSeconds" -> Config.INTER_CHAT_PAIR_LOCK_SECONDS.set(integer);
            case "eventContextEnabled" -> Config.EVENT_CONTEXT_ENABLED.set(bool);
            case "eventContextRange" -> Config.EVENT_CONTEXT_RANGE.set(number);
            case "eventContextMaxBuffered" -> Config.EVENT_CONTEXT_MAX_BUFFERED.set(integer);
            case "eventContextHurtEnabled" -> Config.EVENT_CONTEXT_HURT_ENABLED.set(bool);
            case "eventContextSelfEnabled" -> Config.EVENT_CONTEXT_SELF_ENABLED.set(bool);
            case "eventContextHurtMaxAge" -> Config.EVENT_CONTEXT_HURT_MAX_AGE_SECONDS.set(integer);
            case "chatHistoryMaxStored" -> Config.CHAT_HISTORY_MAX_STORED.set(integer);
            case "prebuiltMinMatchingContexts" -> Config.PREBUILT_MIN_MATCHING_CONTEXTS.set(integer);
            case "prebuiltMaxEntriesPerSource" -> Config.PREBUILT_MAX_ENTRIES_PER_SOURCE.set(integer);
            case "maidIdentityEnabled" -> Config.MAID_IDENTITY_ENABLED.set(bool);
            default -> CiLog.warn("unknown config key in CI action: {}", key);
        }
    }

    private static JsonObject player(JsonObject action, MinecraftServer server) {
        String name = CiJson.str(action, "name", "").toLowerCase(Locale.ROOT);
        ServerPlayer player = findPlayer(server, name);
        if (player == null) {
            throw new IllegalArgumentException("player not online: " + name);
        }
        if (action.has("x")) {
            double x = CiJson.number(action, "x", 0.5);
            double y = CiJson.number(action, "y", 64);
            double z = CiJson.number(action, "z", 0.5);
            float yaw = (float) CiJson.number(action, "yaw", player.getYRot());
            float pitch = (float) CiJson.number(action, "pitch", player.getXRot());
            player.teleportTo(server.overworld(), x, y, z, yaw, pitch);
        }
        if (action.has("gameMode")) {
            GameType type = GameType.byName(CiJson.str(action, "gameMode"), GameType.SURVIVAL);
            player.setGameMode(type);
        }
        if (action.has("health")) {
            player.setHealth((float) CiJson.number(action, "health", player.getMaxHealth()));
        }
        if (CiJson.bool(action, "heal", false)) {
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(20.0F);
            player.clearFire();
            player.removeAllEffects();
        }
        if (action.has("operator")) {
            boolean op = CiJson.bool(action, "operator", false);
            player.setGameMode(op ? GameType.CREATIVE : GameType.SURVIVAL);
            server.getPlayerList().op(player.getGameProfile());
            if (!op) {
                server.getPlayerList().deop(player.getGameProfile());
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("name", player.getName().getString());
        out.addProperty("uuid", player.getUUID().toString());
        out.addProperty("x", player.getX());
        out.addProperty("y", player.getY());
        out.addProperty("z", player.getZ());
        out.addProperty("health", player.getHealth());
        return out;
    }

    private static JsonObject spawnMaid(JsonObject action, MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer owner = findPlayer(server, CiJson.str(action, "owner", ""));
        if (owner == null) {
            throw new IllegalArgumentException("owner not online");
        }
        double x = CiJson.number(action, "x", owner.getX() + 2);
        double y = CiJson.number(action, "y", owner.getY());
        double z = CiJson.number(action, "z", owner.getZ());
        // 走原版 /summon：让 TLM 自己完成实体注册、finalizeSpawn 与持久化标记。
        // 直接 new + addFreshEntity 在真实专服里会因为缺少 TLM 的初始化步骤而很快被移除。
        EntityMaid maid = summonMaid(level, x, y, z);
        if (maid == null) {
            maid = new EntityMaid(level);
            maid.moveTo(x, y, z, 0, 0);
            if (!level.addFreshEntity(maid)) {
                throw new IllegalStateException("maid addFreshEntity returned false");
            }
        }
        maid.moveTo(x, y, z, (float) CiJson.number(action, "yaw", 0), 0);
        maid.setModelId(CiJson.str(action, "modelId", "touhou_little_maid:reimu"));
        if (!maid.isTame()) {
            maid.tame(owner);
        }
        maid.setHealth(maid.getMaxHealth());
        maid.setPersistenceRequired();
        String displayName = CiJson.str(action, "name", "");
        if (!displayName.isBlank()) {
            maid.setCustomName(Component.literal(displayName));
            maid.setCustomNameVisible(false);
        }
        applyMaidData(maid, action);
        maid.setInSittingPose(CiJson.bool(action, "sitting", true));
        JsonObject out = maidSummary(maid);
        out.addProperty("spawned", true);
        lastSpawnId = maid.getId();
        CiLog.info("spawned maid id={} owner={} at={},{},{}", maid.getId(), owner.getName().getString(), x, y, z);
        return out;
    }

    private static EntityMaid summonMaid(ServerLevel level, double x, double y, double z) {
        try {
            String typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(EntityMaid.TYPE).toString();
            var source = level.getServer().createCommandSourceStack().withSuppressedOutput();
            level.getServer().getCommands().performPrefixedCommand(source,
                    "summon " + typeId + " " + x + " " + y + " " + z
                            + " {PersistenceRequired:1b,NoAI:0b}");
            EntityMaid best = null;
            double bestDistance = Double.MAX_VALUE;
            for (EntityMaid candidate : level.getEntitiesOfClass(EntityMaid.class,
                    new AABB(x - 3, y - 3, z - 3, x + 3, y + 3, z + 3))) {
                double distance = candidate.distanceToSqr(x, y, z);
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                CiLog.warn("summon command executed but no EntityMaid found near {}, {}, {}", x, y, z);
            }
            return best;
        } catch (Exception exception) {
            CiLog.warn("summon maid failed: {}", exception.toString());
            return null;
        }
    }

    private static JsonObject updateMaid(JsonObject action, MinecraftServer server) {
        EntityMaid maid = findMaid(server, CiJson.integer(action, "maidId", -1));
        if (maid == null) {
            throw new IllegalArgumentException("maid not found");
        }
        if (action.has("x")) {
            maid.moveTo(CiJson.number(action, "x", maid.getX()), CiJson.number(action, "y", maid.getY()),
                    CiJson.number(action, "z", maid.getZ()), maid.getYRot(), maid.getXRot());
        }
        if (CiJson.bool(action, "heal", false)) {
            maid.setHealth(maid.getMaxHealth());
            maid.clearFire();
            maid.removeAllEffects();
        }
        if (action.has("health")) {
            maid.setHealth((float) CiJson.number(action, "health", maid.getMaxHealth()));
        }
        if (action.has("sitting")) {
            maid.setInSittingPose(CiJson.bool(action, "sitting", false));
        }
        if (CiJson.bool(action, "sleeping", false)) {
            maid.startSleeping(maid.blockPosition());
        } else if (CiJson.bool(action, "wake", false)) {
            maid.stopSleeping();
        }
        applyMaidData(maid, action);
        return maidSummary(maid);
    }

    private static void applyMaidData(EntityMaid maid, JsonObject action) {
        MaidAIChatManager manager = maid.getAiChatManager();
        if (action.has("llmSite")) {
            manager.llmSite = CiJson.str(action, "llmSite");
        } else if (manager.llmSite == null || manager.llmSite.isBlank()) {
            manager.llmSite = "ci_llm";
        }
        if (action.has("llmModel")) {
            manager.llmModel = CiJson.str(action, "llmModel");
        } else if (manager.llmModel == null || manager.llmModel.isBlank()) {
            manager.llmModel = "ci-model";
        }
        if (action.has("ttsSite")) {
            manager.ttsSite = CiJson.str(action, "ttsSite");
        } else if (manager.ttsSite == null || manager.ttsSite.isBlank()) {
            manager.ttsSite = "ci_tts";
        }
        if (action.has("ttsModel")) {
            manager.ttsModel = CiJson.str(action, "ttsModel");
        } else if (manager.ttsModel == null || manager.ttsModel.isBlank()) {
            manager.ttsModel = "ci-voice";
        }
        if (action.has("ttsLanguage")) {
            manager.ttsLanguage = CiJson.str(action, "ttsLanguage");
        } else if (manager.ttsLanguage == null || manager.ttsLanguage.isBlank()) {
            manager.ttsLanguage = "zh_cn";
        }
        if (action.has("chatLanguage")) {
            manager.chatLanguage = CiJson.str(action, "chatLanguage");
        } else if (manager.chatLanguage == null || manager.chatLanguage.isBlank()) {
            manager.chatLanguage = "zh_cn";
        }
        if (action.has("persona")) {
            manager.customSetting = CiJson.str(action, "persona");
        }
        if (action.has("personaStructured")) {
            JsonObject p = CiJson.obj(action, "personaStructured");
            MaidPersonaDraft draft = new MaidPersonaDraft(
                    CiJson.str(p, "identity"), CiJson.str(p, "personality"),
                    CiJson.str(p, "background"), CiJson.str(p, "hobbies"));
            MaidPersonaHost.replace(maid, new MaidPersonaConfig(MaidPersonaMode.STRUCTURED, draft));
        }
        if (action.has("personaMode")) {
            String mode = CiJson.str(action, "personaMode");
            MaidPersonaConfig current = MaidPersonaHost.configOf(maid);
            MaidPersonaMode parsed = "structured".equals(mode) ? MaidPersonaMode.STRUCTURED : MaidPersonaMode.ORIGINAL;
            MaidPersonaHost.replace(maid, current.withMode(parsed));
        }
        manager.ownerName = maid.getOwner() == null ? "" : maid.getOwner().getName().getString();
    }

    private static JsonObject command(JsonObject action, MinecraftServer server) {
        String command = CiJson.str(action, "command");
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        var source = server.createCommandSourceStack().withSuppressedOutput().withPermission(4);
        server.getCommands().performPrefixedCommand(source, command);
        JsonObject out = new JsonObject();
        out.addProperty("command", command);
        out.addProperty("result", 1);
        return out;
    }

    private static JsonObject damage(JsonObject action, MinecraftServer server) {
        Entity entity = findEntity(server, action);
        if (!(entity instanceof LivingEntity living)) {
            throw new IllegalArgumentException("damage target is not living");
        }
        float amount = (float) CiJson.number(action, "amount", 4.0);
        DamageSource source = switch (CiJson.str(action, "source", "generic")) {
            case "player" -> {
                ServerPlayer player = findPlayer(server, CiJson.str(action, "by", "owner"));
                yield player == null ? server.overworld().damageSources().generic()
                        : server.overworld().damageSources().playerAttack(player);
            }
            case "magic" -> server.overworld().damageSources().magic();
            case "fall" -> server.overworld().damageSources().fall();
            case "lava" -> server.overworld().damageSources().lava();
            default -> server.overworld().damageSources().generic();
        };
        boolean hurt = living.hurt(source, amount);
        JsonObject out = new JsonObject();
        out.addProperty("hurt", hurt);
        out.addProperty("health", living.getHealth());
        out.addProperty("id", living.getId());
        return out;
    }

    private static JsonObject kill(JsonObject action, MinecraftServer server) {
        Entity entity = findEntity(server, action);
        if (!(entity instanceof LivingEntity living)) {
            throw new IllegalArgumentException("kill target is not living");
        }
        living.hurt(server.overworld().damageSources().genericKill(), Float.MAX_VALUE);
        JsonObject out = new JsonObject();
        out.addProperty("alive", living.isAlive());
        out.addProperty("id", living.getId());
        return out;
    }

    private static JsonObject spawnAnimal(JsonObject action, MinecraftServer server) {
        ServerLevel level = server.overworld();
        String type = CiJson.str(action, "type", "minecraft:horse");
        var entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .get(net.minecraft.resources.ResourceLocation.parse(type));
        if (entityType == null) {
            throw new IllegalArgumentException("unknown entity type " + type);
        }
        Entity entity = entityType.create(level);
        if (entity == null) {
            throw new IllegalStateException("cannot create entity " + type);
        }
        double x = CiJson.number(action, "x", 2);
        double y = CiJson.number(action, "y", 64);
        double z = CiJson.number(action, "z", 2);
        entity.moveTo(x, y, z, 0, 0);
        if (entity instanceof TamableAnimal tamable) {
            ServerPlayer owner = findPlayer(server, CiJson.str(action, "owner", "owner"));
            if (owner != null) {
                tamable.tame(owner);
            }
        }
        level.addFreshEntity(entity);
        JsonObject out = new JsonObject();
        out.addProperty("id", entity.getId());
        out.addProperty("type", type);
        return out;
    }

    /**
     * 真实神龛复活链路：先用 TLM 的物品胶片把一只女仆存成胶片，再走
     * {@link ItemFilm#filmToMaid} 的正式复活流程（产品 mixin 在 addFreshEntity 处记录复活事实）。
     * 不直接调用产品记录函数。
     */
    private static JsonObject revive(JsonObject action, MinecraftServer server) {
        EntityMaid sourceMaid = findMaid(server, CiJson.integer(action, "sourceMaidId", -1));
        if (sourceMaid == null) {
            throw new IllegalArgumentException("source maid not found");
        }
        ServerPlayer player = findPlayer(server, CiJson.str(action, "player", "owner"));
        if (player == null) {
            throw new IllegalArgumentException("revive player not online");
        }
        ItemStack film = ItemFilm.maidToFilm(sourceMaid);
        double x = CiJson.number(action, "x", sourceMaid.getX());
        double y = CiJson.number(action, "y", sourceMaid.getY());
        double z = CiJson.number(action, "z", sourceMaid.getZ());
        ItemFilm.filmToMaid(film, player.level(), BlockPos.containing(x, y, z), player);
        EntityMaid revived = player.level().getEntitiesOfClass(EntityMaid.class,
                        new AABB(x - 2, y - 2, z - 2, x + 2, y + 2, z + 2),
                        m -> m.isAlive() && !m.getUUID().equals(sourceMaid.getUUID()))
                .stream().max(Comparator.comparingInt(EntityMaid::getId)).orElse(null);
        JsonObject out = new JsonObject();
        out.addProperty("revived", revived != null);
        if (revived != null) {
            out.addProperty("maidId", revived.getId());
            out.addProperty("maidUuid", revived.getUUID().toString());
        }
        return out;
    }

    private static JsonObject removeMaid(JsonObject action, MinecraftServer server) {
        EntityMaid maid = findMaid(server, CiJson.integer(action, "maidId", -1));
        if (maid == null) {
            throw new IllegalArgumentException("maid not found");
        }
        int id = maid.getId();
        maid.discard();
        JsonObject out = new JsonObject();
        out.addProperty("removed", true);
        out.addProperty("maidId", id);
        return out;
    }

    private static JsonObject save(MinecraftServer server) {
        server.saveEverything(true, true, true);
        JsonObject out = new JsonObject();
        out.addProperty("saved", true);
        out.addProperty("tick", server.getTickCount());
        return out;
    }

    private static JsonObject stop(MinecraftServer server) {
        JsonObject out = new JsonObject();
        out.addProperty("stopping", true);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(800L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            server.halt(false);
        }, "ci-server-stop");
        thread.setDaemon(true);
        thread.start();
        return out;
    }

    /** 玩家/单只设置的权威快照，供设置与权限场景核对 C2S 写入结果 */
    private static JsonObject settings(JsonObject action, MinecraftServer server) {
        ServerPlayer player = findPlayer(server, CiJson.str(action, "player", "owner"));
        if (player == null) {
            throw new IllegalArgumentException("player not online");
        }
        UUID playerUuid = player.getUUID();
        JsonObject out = new JsonObject();
        out.addProperty("player", player.getName().getString());
        out.addProperty("uuid", playerUuid.toString());
        out.addProperty("selfTalkGlobal", com.maidmod.selftalk.PlayerSettingsStore
                .isSelfTalkEnabled(server, playerUuid));
        out.addProperty("interChatGlobal", com.maidmod.selftalk.PlayerSettingsStore
                .isInterChatEnabled(server, playerUuid));
        out.addProperty("sleepQuietGlobal", com.maidmod.selftalk.PlayerSettingsStore
                .isSleepQuietGlobal(server, playerUuid));
        out.addProperty("toolGlobal", com.maidmod.selftalk.PlayerSettingsStore
                .isToolCallGlobal(server, playerUuid));
        out.addProperty("promptGlobal", com.maidmod.selftalk.PlayerSettingsStore
                .getCustomPromptGlobal(server, playerUuid));
        out.addProperty("promptOverride", com.maidmod.selftalk.PlayerSettingsStore
                .isCustomPromptOverrideEnabled(server, playerUuid));
        out.addProperty("historyMode", com.maidmod.selftalk.PlayerSettingsStore
                .getHistoryContextMode(server, playerUuid).id());
        out.addProperty("envNaturalLanguage", com.maidmod.selftalk.PlayerSettingsStore
                .isEnvironmentContextNaturalLanguageEnabled(server, playerUuid));
        out.addProperty("reuseGlobal", com.maidmod.selftalk.PlayerSettingsStore
                .isPrebuiltReuseEnabled(server, playerUuid));
        out.addProperty("reuseFollowingServer", com.maidmod.selftalk.PlayerSettingsStore
                .isPrebuiltReuseFollowingServer(server, playerUuid));
        out.addProperty("reusePlayerMinEligible", com.maidmod.selftalk.PlayerSettingsStore
                .getPrebuiltReusePlayerMinEligible(server, playerUuid));
        out.addProperty("reusePlayerHitChance", com.maidmod.selftalk.PlayerSettingsStore
                .getPrebuiltReusePlayerHitChance(server, playerUuid));
        out.addProperty("reuseServerMinEligible", com.maidmod.selftalk.PlayerSettingsStore
                .getPrebuiltReuseServerMinEligible(server));
        out.addProperty("reuseServerHitChance", com.maidmod.selftalk.PlayerSettingsStore
                .getPrebuiltReuseServerHitChance(server));
        out.addProperty("reusePlayerParametersAllowed", com.maidmod.selftalk.PlayerSettingsStore
                .arePrebuiltReusePlayerParametersAllowed(server));
        out.addProperty("prebuiltAudioEnabled", com.maidmod.selftalk.PlayerSettingsStore
                .isPrebuiltAudioEnabled(server));
        out.addProperty("op", server.getPlayerList().isOp(player.getGameProfile()));
        JsonObject env = new JsonObject();
        com.maidmod.selftalk.PlayerSettingsStore.getEnvironmentContextOverrides(server, playerUuid)
                .forEach((key, mode) -> env.addProperty(key, mode.id()));
        out.add("envModes", env);
        JsonArray maids = new JsonArray();
        for (EntityMaid maid : server.overworld().getEntitiesOfClass(EntityMaid.class,
                new AABB(-30000000, -64, -30000000, 30000000, 320, 30000000))) {
            JsonObject m = new JsonObject();
            m.addProperty("id", maid.getId());
            m.addProperty("uuid", maid.getUUID().toString());
            m.addProperty("selfTalkForMaid", com.maidmod.selftalk.PlayerSettingsStore
                    .isSelfTalkEnabledForMaid(server, maid));
            m.addProperty("interChatForMaid", com.maidmod.selftalk.PlayerSettingsStore
                    .isInterChatEnabledForMaid(server, maid));
            m.addProperty("sleepQuietForMaid", com.maidmod.selftalk.PlayerSettingsStore
                    .isSleepQuietForMaid(server, maid));
            m.addProperty("toolForMaid", com.maidmod.selftalk.PlayerSettingsStore
                    .isToolCallEnabledForMaid(server, maid));
            m.addProperty("reuseForMaid", com.maidmod.selftalk.PlayerSettingsStore
                    .isPrebuiltReuseEnabledForMaid(server, maid));
            maids.add(m);
        }
        out.add("maids", maids);
        return out;
    }

    /** 完整状态快照：Python 报告与断言只看这里，不读进程内存 */
    private static JsonObject query(MinecraftServer server) {
        JsonObject out = new JsonObject();
        out.addProperty("tick", server.getTickCount());
        out.addProperty("time", System.currentTimeMillis());
        ServerLevel level = server.overworld();
        out.addProperty("dayTime", level.getDayTime());
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject p = new JsonObject();
            p.addProperty("name", player.getName().getString());
            p.addProperty("uuid", player.getUUID().toString());
            p.addProperty("x", player.getX());
            p.addProperty("y", player.getY());
            p.addProperty("z", player.getZ());
            p.addProperty("health", player.getHealth());
            p.addProperty("gameMode", player.gameMode.getGameModeForPlayer().getName());
            players.add(p);
        }
        out.add("players", players);
        if (lastSpawnId >= 0) {
            net.minecraft.world.entity.Entity tracked = level.getEntity(lastSpawnId);
            out.addProperty("lastSpawnId", lastSpawnId);
            out.addProperty("lastSpawnPresent", tracked != null);
            if (tracked != null) {
                out.addProperty("lastSpawnRemoved", tracked.isRemoved());
                out.addProperty("lastSpawnAlive", tracked.isAlive());
                out.addProperty("lastSpawnClass", tracked.getClass().getSimpleName());
                out.addProperty("lastSpawnX", tracked.getX());
                out.addProperty("lastSpawnY", tracked.getY());
                out.addProperty("lastSpawnZ", tracked.getZ());
            }
        }
        JsonArray maids = new JsonArray();
        for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, new AABB(-30000000, -64, -30000000,
                30000000, 320, 30000000))) {
            maids.add(maidSnapshot(maid, server));
        }
        out.add("maids", maids);
        // 诊断：实体是否真的进入 level 的实体表；只在 maids 为空时附带，正常场景开销可忽略。
        if (maids.isEmpty()) {
            JsonArray entityKinds = new JsonArray();
            for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
                entityKinds.add(entity.getClass().getSimpleName() + "#" + entity.getId());
                if (entityKinds.size() >= 30) {
                    break;
                }
            }
            out.add("entityKinds", entityKinds);
        }
        out.add("config", configSnapshot());
        JsonArray mods = new JsonArray();
        for (net.neoforged.fml.ModContainer container : net.neoforged.fml.ModList.get().getSortedMods()) {
            mods.add(container.getModId());
        }
        out.add("mods", mods);
        out.addProperty("epoch", System.currentTimeMillis());
        return out;
    }

    private static JsonObject configSnapshot() {
        JsonObject c = new JsonObject();
        c.addProperty("enabled", Config.ENABLED.get());
        c.addProperty("playerOptionEnabled", Config.PLAYER_OPTION_ENABLED.get());
        c.addProperty("toolCallEnabled", Config.TOOL_CALL_ENABLED.get());
        c.addProperty("state1Enabled", Config.STATE1_ENABLED.get());
        c.addProperty("state1Min", Config.STATE1_MIN_INTERVAL.get());
        c.addProperty("state1Max", Config.STATE1_MAX_INTERVAL.get());
        c.addProperty("state1Range", Config.STATE1_PLAYER_RANGE.get());
        c.addProperty("state1Keep", Config.STATE1_KEEP_SELF_TALK_COUNT.get());
        c.addProperty("state2Enabled", Config.STATE2_ENABLED.get());
        c.addProperty("welcomeEnabled", Config.WELCOME_ENABLED.get());
        c.addProperty("welcomeWindow", Config.WELCOME_WINDOW_TICKS.get());
        c.addProperty("maxTriggerPerSecond", Config.MAX_TRIGGER_PER_SECOND.get());
        c.addProperty("selfTalkMinInterval", Config.SELF_TALK_MIN_INTERVAL.get());
        c.addProperty("selfTalkMaxInterval", Config.SELF_TALK_MAX_INTERVAL.get());
        c.addProperty("deferQueueMax", Config.DEFER_QUEUE_MAX.get());
        c.addProperty("interChatEnabled", Config.INTER_CHAT_ENABLED.get());
        c.addProperty("interChatMin", Config.INTER_CHAT_MIN_INTERVAL.get());
        c.addProperty("interChatMax", Config.INTER_CHAT_MAX_INTERVAL.get());
        c.addProperty("interChatChainProbability", Config.INTER_CHAT_CHAIN_PROBABILITY.get());
        c.addProperty("interChatMaxChainRounds", Config.INTER_CHAT_MAX_CHAIN_ROUNDS.get());
        c.addProperty("interChatPairLockSeconds", Config.INTER_CHAT_PAIR_LOCK_SECONDS.get());
        c.addProperty("eventContextEnabled", Config.EVENT_CONTEXT_ENABLED.get());
        c.addProperty("eventContextRange", Config.EVENT_CONTEXT_RANGE.get());
        c.addProperty("eventContextHurtEnabled", Config.EVENT_CONTEXT_HURT_ENABLED.get());
        c.addProperty("eventContextSelfEnabled", Config.EVENT_CONTEXT_SELF_ENABLED.get());
        c.addProperty("chatHistoryMaxStored", Config.CHAT_HISTORY_MAX_STORED.get());
        c.addProperty("prebuiltMinMatchingContexts", Config.PREBUILT_MIN_MATCHING_CONTEXTS.get());
        c.addProperty("prebuiltMaxEntriesPerSource", Config.PREBUILT_MAX_ENTRIES_PER_SOURCE.get());
        return c;
    }

    private static JsonObject maidSnapshot(EntityMaid maid, MinecraftServer server) {
        JsonObject out = maidSummary(maid);
        MaidAIChatManager manager = maid.getAiChatManager();
        out.addProperty("llmSite", manager.llmSite);
        out.addProperty("llmModel", manager.llmModel);
        out.addProperty("ttsSite", manager.ttsSite);
        out.addProperty("ttsModel", manager.ttsModel);
        out.addProperty("ttsLanguage", manager.ttsLanguage);
        out.addProperty("chatLanguage", manager.chatLanguage);
        out.addProperty("customSetting", manager.customSetting);
        JsonObject persona = new JsonObject();
        MaidPersonaConfig config = MaidPersonaHost.configOf(maid);
        persona.addProperty("mode", config.mode().tag());
        persona.addProperty("identity", config.draft().identity());
        persona.addProperty("personality", config.draft().personality());
        persona.addProperty("background", config.draft().background());
        persona.addProperty("hobbies", config.draft().hobbies());
        out.add("persona", persona);
        JsonArray history = new JsonArray();
        manager.getHistory().getDeque().forEach(message -> history.add(messageSnapshot(message)));
        out.add("history", history);
        out.addProperty("historySummary", manager.getCompressedSummary());
        out.addProperty("lastTokenUsage", manager.getLastChatTokenUsage());
        JsonArray archive = new JsonArray();
        AutonomousChatHistory autonomous = AutonomousChatHistoryHost.of(maid);
        if (autonomous != null) {
            for (AutonomousChatRecord record : autonomous.visibleArchive()) {
                JsonObject r = new JsonObject();
                r.addProperty("id", record.id().toString());
                r.addProperty("source", record.source().key());
                r.addProperty("seq", record.seq());
                r.addProperty("gameTime", record.gameTime());
                r.addProperty("speakerId", record.speakerId() == null ? "" : record.speakerId().toString());
                r.addProperty("speakerName", record.speakerName());
                r.addProperty("message", record.message());
                r.addProperty("chatText", record.chatText());
                archive.add(r);
            }
        }
        out.add("archive", archive);
        JsonArray bubbles = new JsonArray();
        var bubbleIterator = maid.getChatBubbleManager().getChatBubbleDataCollection().iterator();
        while (bubbleIterator.hasNext()) {
            bubbles.add(bubbleSnapshot(bubbleIterator.next()));
        }
        out.add("bubbles", bubbles);
        out.add("state", stateSnapshot(maid));
        JsonObject reuse = new JsonObject();
        try {
            String fingerprint = SelfTalkReuseService.fingerprintOf(maid);
            reuse.addProperty("fingerprint", fingerprint);
            SelfTalkReuseStore store = SelfTalkReuseStore.of(server);
            reuse.add("selfTalk", reuseEntries(store, fingerprint, SelfTalkReuseStore.SOURCE_SELF_TALK));
            reuse.add("welcome", reuseEntries(store, fingerprint, SelfTalkReuseStore.SOURCE_WELCOME));
        } catch (Throwable t) {
            reuse.addProperty("error", t.toString());
        }
        out.add("reuse", reuse);
        return out;
    }

    private static JsonArray reuseEntries(SelfTalkReuseStore store, String fingerprint, String source) {
        JsonArray array = new JsonArray();
        try {
            for (SelfTalkReuseStore.PrebuiltEntry entry : store.entries(fingerprint, source)) {
                JsonObject e = new JsonObject();
                e.addProperty("id", entry.entryId.toString());
                e.addProperty("createdAt", entry.createdAt);
                e.addProperty("chat", entry.chatText);
                e.addProperty("tts", entry.ttsText);
                e.addProperty("chatLanguage", entry.chatLanguage);
                e.addProperty("ttsLanguage", entry.ttsLanguage);
                e.addProperty("origin", entry.origin.name());
                e.add("env", CiJson.object());
                array.add(e);
            }
        } catch (Throwable ignored) {
            // 库不可用时返回空数组，由 Python 直接读文件补充判断
        }
        return array;
    }

    private static JsonObject stateSnapshot(EntityMaid maid) {
        JsonObject s = new JsonObject();
        SelfTalkState.State state = SelfTalkState.peek(maid.getId());
        if (state == null) {
            s.addProperty("exists", false);
            return s;
        }
        s.addProperty("exists", true);
        s.addProperty("nextTriggerTick", state.nextTriggerTick);
        s.addProperty("selfTalkPending", state.selfTalkPending);
        s.addProperty("selfTalkPendingSinceTick", state.selfTalkPendingSinceTick);
        s.addProperty("playerChatCount", state.playerChatCount);
        s.addProperty("windowSelfTalkCount", state.windowSelfTalkMsgs.size());
        s.addProperty("nextInterChatTriggerTick", state.nextInterChatTriggerTick);
        s.addProperty("interChatPending", state.interChatPending);
        s.addProperty("interChatPendingSinceTick", state.interChatPendingSinceTick);
        s.addProperty("windowInterChatCount", state.windowInterChatMsgs.size());
        s.addProperty("deferredCount", state.deferredRequests.size());
        s.addProperty("planning", state.isPlanning());
        s.addProperty("pendingPlans", state.pendingPlans.size());
        s.addProperty("hasSelfTalkCallback", state.currentSelfTalkCallback != null);
        s.addProperty("hasInterChatRequest", state.currentInterChatRequest != null);
        s.addProperty("welcomedPlayers", state.welcomedPlayers.size());
        JsonArray deferred = new JsonArray();
        state.deferredRequests.forEach(request -> deferred.add(request.kind().name()));
        s.add("deferred", deferred);
        return s;
    }

    private static JsonObject messageSnapshot(LLMMessage message) {
        JsonObject m = new JsonObject();
        m.addProperty("role", message.role().name());
        m.addProperty("message", message.message());
        m.addProperty("gameTime", message.gameTime());
        m.addProperty("toolCallId", message.toolCallId() == null ? "" : message.toolCallId());
        JsonArray calls = new JsonArray();
        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                JsonObject c = new JsonObject();
                c.addProperty("id", call.getId() == null ? "" : call.getId());
                FunctionToolCall function = call.getFunction();
                c.addProperty("name", function == null || function.getName() == null ? "" : function.getName());
                c.addProperty("arguments", function == null || function.getArguments() == null
                        ? "" : function.getArguments());
                calls.add(c);
            }
        }
        m.add("toolCalls", calls);
        return m;
    }

    private static JsonObject bubbleSnapshot(IChatBubbleData bubble) {
        JsonObject b = new JsonObject();
        b.addProperty("type", bubble.id().toString());
        b.addProperty("waiting", bubble instanceof WaitingChatBubbleData);
        b.addProperty("autonomousWaiting", SelfTalkWaitingBubbleData.isAutonomous(bubble));
        String text = "";
        if (bubble instanceof TextChatBubbleData) {
            text = CiJson.textOf(CiJson.field(bubble, TextChatBubbleData.class, "text"));
        } else if (bubble instanceof WaitingChatBubbleData) {
            text = CiJson.textOf(CiJson.field(bubble, WaitingChatBubbleData.class, "text"));
            String secondary = CiJson.textOf(CiJson.field(bubble, WaitingChatBubbleData.class, "secondaryText"));
            if (!secondary.isBlank()) {
                text = text + " / " + secondary;
            }
        }
        b.addProperty("text", text);
        return b;
    }

    private static JsonObject maidSummary(EntityMaid maid) {
        JsonObject out = new JsonObject();
        out.addProperty("id", maid.getId());
        out.addProperty("uuid", maid.getUUID().toString());
        out.addProperty("ownerUuid", maid.getOwnerUUID() == null ? "" : maid.getOwnerUUID().toString());
        out.addProperty("ownerName", maid.getOwner() == null ? "" : maid.getOwner().getName().getString());
        out.addProperty("x", maid.getX());
        out.addProperty("y", maid.getY());
        out.addProperty("z", maid.getZ());
        out.addProperty("alive", maid.isAlive());
        out.addProperty("health", maid.getHealth());
        out.addProperty("maxHealth", maid.getMaxHealth());
        out.addProperty("sitting", maid.isMaidInSittingPose());
        out.addProperty("sleeping", maid.isSleeping());
        out.addProperty("modelId", maid.getModelId());
        out.addProperty("customName", maid.getCustomName() == null ? "" : maid.getCustomName().getString());
        return out;
    }

    private static Entity findEntity(MinecraftServer server, JsonObject action) {
        if (action.has("maidId")) {
            EntityMaid maid = findMaid(server, CiJson.integer(action, "maidId", -1));
            if (maid == null) {
                throw new IllegalArgumentException("maid not found: " + CiJson.integer(action, "maidId", -1));
            }
            return maid;
        }
        String name = CiJson.str(action, "player", "");
        if (!name.isBlank()) {
            ServerPlayer player = findPlayer(server, name);
            if (player == null) {
                throw new IllegalArgumentException("player not found: " + name);
            }
            return player;
        }
        if (action.has("entityId")) {
            Entity entity = server.overworld().getEntity(CiJson.integer(action, "entityId", -1));
            if (entity == null) {
                throw new IllegalArgumentException("entity not found");
            }
            return entity;
        }
        throw new IllegalArgumentException("no target");
    }

    private static EntityMaid findMaid(MinecraftServer server, int id) {
        if (id < 0) {
            return null;
        }
        Entity entity = server.overworld().getEntity(id);
        return entity instanceof EntityMaid maid ? maid : null;
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) {
            name = "owner";
        }
        if ("owner".equalsIgnoreCase(name)) {
            name = "OwnerBot";
        } else if ("other".equalsIgnoreCase(name)) {
            name = "OtherBot";
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equalsIgnoreCase(name)
                    || player.getGameProfile().getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }
}
