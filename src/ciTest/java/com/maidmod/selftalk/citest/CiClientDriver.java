package com.maidmod.selftalk.citest;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.WaitingChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.ClearMaidAIDataPacket;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendUserChatPackage;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.OpenMaidAIChatPacket;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.SelfTalkWaitingBubbleData;
import com.maidmod.selftalk.client.MaidPersonaEditScreen;
import com.maidmod.selftalk.client.SelfTalkPlayerSettingsScreen;
import com.maidmod.selftalk.network.CustomPromptConfigSetPayload;
import com.maidmod.selftalk.network.EnvironmentContextConfigSetPayload;
import com.maidmod.selftalk.network.HistoryContextModeSetPayload;
import com.maidmod.selftalk.network.InterChatConfigSetPayload;
import com.maidmod.selftalk.network.MaidPersonaSavePayload;
import com.maidmod.selftalk.network.MaidPersonaSplitRequestPayload;
import com.maidmod.selftalk.network.ReuseConfigSetPayload;
import com.maidmod.selftalk.network.SelfTalkConfigSetPayload;
import com.maidmod.selftalk.network.SleepQuietConfigSetPayload;
import com.maidmod.selftalk.network.ToolConfigSetPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 客户端测试驱动：所有操作都在客户端主线程执行，通过真实界面／网络包触发，
 * 不直接调用服务端业务方法，也不伪造服务端回包。
 */
public final class CiClientDriver {

    private CiClientDriver() {
    }

    public static JsonObject execute(String op, JsonObject action, Minecraft mc) {
        return switch (op) {
            case "client.ping" -> ping(mc);
            case "client.open_ai_chat" -> openAiChat(action, mc);
            case "client.open_persona" -> openPersona(action, mc);
            case "client.open_settings" -> openSettings(action, mc);
            case "client.close_screen" -> closeScreen(mc);
            case "client.click_label" -> clickLabel(action, mc);
            case "client.set_edit" -> setEdit(action, mc);
            case "client.chat" -> chat(action, mc);
            case "client.clear" -> clear(action, mc);
            case "client.set_waiting_bubbles" -> setWaitingBubbles(action, mc);
            case "client.send_payload" -> sendPayload(action, mc);
            case "client.query" -> query(mc);
            case "client.probe_clear" -> probeClear();
            case "client.quit" -> quit(mc);
            default -> throw new IllegalArgumentException("unknown client op: " + op);
        };
    }

    private static JsonObject ping(Minecraft mc) {
        JsonObject out = new JsonObject();
        out.addProperty("tick", mc.level == null ? 0 : mc.level.getGameTime());
        out.addProperty("inWorld", mc.player != null && mc.level != null);
        out.addProperty("player", mc.player == null ? "" : mc.player.getName().getString());
        out.addProperty("screen", mc.screen == null ? "" : mc.screen.getClass().getName());
        out.addProperty("ping", mc.getConnection() == null ? -1 : mc.getConnection().getPlayerInfo(
                mc.player == null ? UUID.randomUUID() : mc.player.getUUID()) == null ? -1
                : mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency());
        return out;
    }

    private static JsonObject openAiChat(JsonObject action, Minecraft mc) {
        EntityMaid maid = findMaid(action, mc);
        PacketDistributor.sendToServer(new OpenMaidAIChatPacket(maid));
        JsonObject out = new JsonObject();
        out.addProperty("sent", true);
        out.addProperty("maidId", maid.getId());
        return out;
    }

    private static JsonObject openPersona(JsonObject action, Minecraft mc) {
        EntityMaid maid = findMaid(action, mc);
        mc.setScreen(new MaidPersonaEditScreen(null, maid));
        JsonObject out = new JsonObject();
        out.addProperty("opened", true);
        out.addProperty("maidId", maid.getId());
        return out;
    }

    private static JsonObject openSettings(JsonObject action, Minecraft mc) {
        EntityMaid maid = findMaid(action, mc);
        mc.setScreen(new SelfTalkPlayerSettingsScreen(maid));
        JsonObject out = new JsonObject();
        out.addProperty("opened", true);
        out.addProperty("maidId", maid.getId());
        return out;
    }

    private static JsonObject closeScreen(Minecraft mc) {
        Screen screen = mc.screen;
        mc.setScreen(null);
        JsonObject out = new JsonObject();
        out.addProperty("closed", screen != null);
        out.addProperty("previous", screen == null ? "" : screen.getClass().getName());
        return out;
    }

    private static JsonObject clickLabel(JsonObject action, Minecraft mc) {
        Screen screen = mc.screen;
        if (screen == null) {
            throw new IllegalStateException("no screen");
        }
        String wanted = CiJson.str(action, "label");
        int index = CiJson.integer(action, "index", 0);
        boolean contains = CiJson.bool(action, "contains", true);
        List<Button> matches = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof Button button && button.visible) {
                if (labelMatches(button.getMessage(), wanted, contains)) {
                    matches.add(button);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("button not found: " + wanted + " in " + screen.getClass().getSimpleName());
        }
        if (index < 0 || index >= matches.size()) {
            throw new IllegalStateException("button index out of range: " + index + " / " + matches.size());
        }
        Button button = matches.get(index);
        String label = button.getMessage().getString();
        button.onPress();
        JsonObject out = new JsonObject();
        out.addProperty("clicked", label);
        out.addProperty("active", button.active);
        out.addProperty("matches", matches.size());
        return out;
    }

    private static boolean labelMatches(Component message, String wanted, boolean contains) {
        if (message == null) {
            return false;
        }
        String text = message.getString();
        if (contains ? text.contains(wanted) : text.equals(wanted)) {
            return true;
        }
        if (message.getContents() instanceof TranslatableContents translatable) {
            String key = translatable.getKey();
            return contains ? key.contains(wanted) : key.equals(wanted);
        }
        return false;
    }

    private static JsonObject setEdit(JsonObject action, Minecraft mc) {
        Screen screen = mc.screen;
        if (screen == null) {
            throw new IllegalStateException("no screen");
        }
        int index = CiJson.integer(action, "index", 0);
        String value = CiJson.str(action, "value");
        List<Object> boxes = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof MultiLineEditBox || child instanceof EditBox) {
                boxes.add(child);
            }
        }
        if (index < 0 || index >= boxes.size()) {
            throw new IllegalStateException("edit box index out of range: " + index + " / " + boxes.size());
        }
        Object box = boxes.get(index);
        if (box instanceof MultiLineEditBox multiLine) {
            multiLine.setValue(value);
        } else {
            ((EditBox) box).setValue(value);
        }
        JsonObject out = new JsonObject();
        out.addProperty("set", true);
        out.addProperty("index", index);
        out.addProperty("valueLength", value.length());
        return out;
    }

    private static JsonObject chat(JsonObject action, Minecraft mc) {
        EntityMaid maid = findMaid(action, mc);
        String message = CiJson.str(action, "message");
        String language = CiJson.str(action, "language",
                mc.getLanguageManager().getSelected());
        ChatClientInfo info = ChatClientInfo.fromMaid(maid);
        ChatClientInfo withLanguage = new ChatClientInfo(language, info.name(), info.description());
        PacketDistributor.sendToServer(new SendUserChatPackage(maid.getId(), message, withLanguage));
        JsonObject out = new JsonObject();
        out.addProperty("sent", true);
        out.addProperty("maidId", maid.getId());
        out.addProperty("language", language);
        return out;
    }

    private static JsonObject setWaitingBubbles(JsonObject action, Minecraft mc) {
        boolean enabled = CiJson.bool(action, "enabled", true);
        com.maidmod.selftalk.client.SelfTalkClientConfig.setShowAutonomousWaitingBubbles(enabled);
        JsonObject out = new JsonObject();
        out.addProperty("enabled", com.maidmod.selftalk.client.SelfTalkClientConfig
                .showAutonomousWaitingBubbles());
        return out;
    }

    private static JsonObject clear(JsonObject action, Minecraft mc) {        EntityMaid maid = findMaid(action, mc);
        PacketDistributor.sendToServer(new ClearMaidAIDataPacket(maid.getId()));
        JsonObject out = new JsonObject();
        out.addProperty("sent", true);
        out.addProperty("maidId", maid.getId());
        return out;
    }

    private static JsonObject sendPayload(JsonObject action, Minecraft mc) {
        EntityMaid maid = findMaid(action, mc);
        String kind = CiJson.str(action, "kind");
        UUID session = action.has("sessionId")
                ? UUID.fromString(CiJson.str(action, "sessionId")) : UUID.randomUUID();
        long seq = CiJson.integer(action, "seq", 1);
        switch (kind) {
            case "self_talk" -> PacketDistributor.sendToServer(new SelfTalkConfigSetPayload(
                    optional(action, "maidScoped", maid.getUUID()),
                    CiJson.bool(action, "enabled", true)));
            case "inter_chat" -> PacketDistributor.sendToServer(new InterChatConfigSetPayload(
                    optional(action, "maidScoped", maid.getUUID()),
                    CiJson.bool(action, "enabled", true)));
            case "sleep" -> PacketDistributor.sendToServer(new SleepQuietConfigSetPayload(
                    optional(action, "maidScoped", maid.getUUID()),
                    CiJson.bool(action, "enabled", true)));
            case "tool" -> PacketDistributor.sendToServer(new ToolConfigSetPayload(
                    optional(action, "maidScoped", maid.getUUID()),
                    CiJson.bool(action, "enabled", true)));
            case "prompt" -> {
                Optional<UUID> maidUuid = action.has("promptMaidScoped") && CiJson.bool(action, "promptMaidScoped", false)
                        ? Optional.of(maid.getUUID()) : Optional.empty();
                Optional<String> prompt = action.has("prompt")
                        ? Optional.of(CiJson.str(action, "prompt")) : Optional.empty();
                Optional<Boolean> override = action.has("override")
                        ? Optional.of(CiJson.bool(action, "override", false)) : Optional.empty();
                PacketDistributor.sendToServer(new CustomPromptConfigSetPayload(maidUuid, prompt, override));
            }
            case "history" -> PacketDistributor.sendToServer(new HistoryContextModeSetPayload(
                    session, seq, CiJson.str(action, "mode", "full")));
            case "env" -> PacketDistributor.sendToServer(new EnvironmentContextConfigSetPayload(
                    session, seq, CiJson.str(action, "key"), CiJson.str(action, "mode", "always")));
            case "reuse" -> PacketDistributor.sendToServer(new ReuseConfigSetPayload(
                    session, seq,
                    action.has("reuseMaidScoped") && CiJson.bool(action, "reuseMaidScoped", false)
                            ? maid.getUUID() : null,
                    CiJson.integer(action, "opcode", 0), CiJson.integer(action, "value", 0)));
            case "persona_save" -> {
                JsonObject p = CiJson.obj(action, "persona");
                boolean confirm = CiJson.bool(action, "confirm", true);
                PacketDistributor.sendToServer(new MaidPersonaSavePayload(
                        UUID.randomUUID(), maid.getId(), maid.getUUID(), confirm,
                        CiJson.str(p, "mode", "structured"),
                        CiJson.str(p, "identity"), CiJson.str(p, "personality"),
                        CiJson.str(p, "background"), CiJson.str(p, "hobbies")));
            }
            case "persona_split" -> PacketDistributor.sendToServer(new MaidPersonaSplitRequestPayload(
                    UUID.randomUUID(), maid.getId(), maid.getUUID(),
                    CiJson.str(action, "sourceText", "测试人设")));
            case "clear" -> PacketDistributor.sendToServer(new ClearMaidAIDataPacket(maid.getId()));
            default -> throw new IllegalArgumentException("unknown payload kind: " + kind);
        }
        JsonObject out = new JsonObject();
        out.addProperty("sent", true);
        out.addProperty("kind", kind);
        return out;
    }

    private static Optional<UUID> optional(JsonObject action, String key, UUID maidUuid) {
        return CiJson.bool(action, key, false) ? Optional.of(maidUuid) : Optional.empty();
    }

    private static JsonObject query(Minecraft mc) {
        JsonObject out = new JsonObject();
        out.addProperty("tick", mc.level == null ? 0 : mc.level.getGameTime());
        out.addProperty("inWorld", mc.player != null && mc.level != null);
        if (mc.player != null) {
            JsonObject player = new JsonObject();
            player.addProperty("name", mc.player.getName().getString());
            player.addProperty("uuid", mc.player.getUUID().toString());
            player.addProperty("x", mc.player.getX());
            player.addProperty("y", mc.player.getY());
            player.addProperty("z", mc.player.getZ());
            player.addProperty("health", mc.player.getHealth());
            out.add("player", player);
        }
        out.add("screen", screenSnapshot(mc));
        out.add("chat", CiClientProbe.chatSnapshot());
        out.add("tts", CiClientProbe.ttsSnapshot());
        out.addProperty("renderedBubbles", CiClientProbe.bubbleRenderCount());
        out.addProperty("renderedWaitingBubbles", CiClientProbe.waitingRenderCount());
        JsonArray maids = new JsonArray();
        if (mc.level != null) {
            for (EntityMaid maid : mc.level.getEntitiesOfClass(EntityMaid.class,
                    new AABB(-30000000, -64, -30000000, 30000000, 320, 30000000))) {
                maids.add(maidSnapshot(maid));
            }
        }
        out.add("maids", maids);
        return out;
    }

    private static JsonObject probeClear() {
        CiClientProbe.clear();
        JsonObject out = new JsonObject();
        out.addProperty("cleared", true);
        return out;
    }

    private static JsonObject quit(Minecraft mc) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(800L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mc.stop();
        }, "ci-client-stop");
        thread.setDaemon(true);
        thread.start();
        JsonObject out = new JsonObject();
        out.addProperty("stopping", true);
        return out;
    }

    private static JsonObject screenSnapshot(Minecraft mc) {
        JsonObject out = new JsonObject();
        Screen screen = mc.screen;
        if (screen == null) {
            out.addProperty("open", false);
            return out;
        }
        out.addProperty("open", true);
        out.addProperty("class", screen.getClass().getName());
        out.addProperty("title", screen.getTitle() == null ? "" : screen.getTitle().getString());
        JsonArray widgets = new JsonArray();
        int editIndex = 0;
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget) {
                JsonObject w = new JsonObject();
                w.addProperty("type", widget.getClass().getName());
                w.addProperty("label", widget.getMessage() == null ? "" : widget.getMessage().getString());
                w.addProperty("key", widget.getMessage() != null
                        && widget.getMessage().getContents() instanceof TranslatableContents t ? t.getKey() : "");
                w.addProperty("x", widget.getX());
                w.addProperty("y", widget.getY());
                w.addProperty("width", widget.getWidth());
                w.addProperty("height", widget.getHeight());
                w.addProperty("active", widget.active);
                w.addProperty("visible", widget.visible);
                if (widget instanceof MultiLineEditBox box) {
                    w.addProperty("editIndex", editIndex++);
                    w.addProperty("value", box.getValue());
                } else if (widget instanceof EditBox box) {
                    w.addProperty("editIndex", editIndex++);
                    w.addProperty("value", box.getValue());
                }
                widgets.add(w);
            }
        }
        out.add("widgets", widgets);
        out.addProperty("editCount", editIndex);
        return out;
    }

    private static JsonObject maidSnapshot(EntityMaid maid) {
        JsonObject out = new JsonObject();
        out.addProperty("id", maid.getId());
        out.addProperty("uuid", maid.getUUID().toString());
        out.addProperty("x", maid.getX());
        out.addProperty("y", maid.getY());
        out.addProperty("z", maid.getZ());
        out.addProperty("alive", maid.isAlive());
        out.addProperty("sitting", maid.isMaidInSittingPose());
        out.addProperty("sleeping", maid.isSleeping());
        out.addProperty("ownerUUID", maid.getOwnerUUID() == null ? "" : maid.getOwnerUUID().toString());
        JsonObject persona = new JsonObject();
        var config = MaidPersonaHost.configOf(maid);
        persona.addProperty("mode", config.mode().tag());
        persona.addProperty("identity", config.draft().identity());
        persona.addProperty("personality", config.draft().personality());
        persona.addProperty("background", config.draft().background());
        persona.addProperty("hobbies", config.draft().hobbies());
        out.add("persona", persona);
        out.addProperty("historySize", maid.getAiChatManager().getHistory().size());
        JsonArray bubbles = new JsonArray();
        var bubbleIterator = maid.getChatBubbleManager().getChatBubbleDataCollection().iterator();
        while (bubbleIterator.hasNext()) {
            IChatBubbleData bubble = bubbleIterator.next();
            JsonObject b = new JsonObject();
            b.addProperty("type", bubble.id().toString());
            b.addProperty("waiting", bubble instanceof WaitingChatBubbleData);
            b.addProperty("autonomousWaiting", SelfTalkWaitingBubbleData.isAutonomous(bubble));
            String text = "";
            if (bubble instanceof TextChatBubbleData) {
                text = CiJson.textOf(CiJson.field(bubble, TextChatBubbleData.class, "text"));
            } else if (bubble instanceof WaitingChatBubbleData) {
                text = CiJson.textOf(CiJson.field(bubble, WaitingChatBubbleData.class, "text"));
            }
            b.addProperty("text", text);
            bubbles.add(b);
        }
        out.add("bubbles", bubbles);
        return out;
    }

    private static EntityMaid findMaid(JsonObject action, Minecraft mc) {
        if (mc.level == null) {
            throw new IllegalStateException("not in world");
        }
        int id = CiJson.integer(action, "maidId", -1);
        Entity entity = mc.level.getEntity(id);
        if (entity instanceof EntityMaid maid) {
            return maid;
        }
        if (action.has("maidUuid")) {
            UUID uuid = UUID.fromString(CiJson.str(action, "maidUuid"));
            EntityMaid found = mc.level.getEntitiesOfClass(EntityMaid.class,
                            new AABB(-30000000, -64, -30000000, 30000000, 320, 30000000))
                    .stream().filter(m -> m.getUUID().equals(uuid)).findFirst().orElse(null);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalStateException("maid not found on client: " + id);
    }
}
