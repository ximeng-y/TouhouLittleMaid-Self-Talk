package com.maidmod.selftalk.citest;

import com.google.gson.JsonObject;
import com.maidmod.selftalk.MaidChatReplyEvent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** 服务端事件钩子：只负责启动桥接与上报真实业务事件 */
public final class CiServerHooks {

    private CiServerHooks() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        CiBridge.start(new ServerPlatform(server));
        JsonObject data = new JsonObject();
        data.addProperty("tick", server.getTickCount());
        data.addProperty("world", server.overworld().dimension().location().toString());
        CiBridge.observe("server.started", data);
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        CiBridge.observe("server.stopping", new JsonObject());
        CiBridge.stop();
    }

    public static void onMaidReply(MaidChatReplyEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("maidId", event.getMaid().getId());
        data.addProperty("maidUuid", event.getMaid().getUUID().toString());
        data.addProperty("text", event.getChatText());
        data.addProperty("welcome", event.isWelcome());
        data.addProperty("tick", event.getMaid().level().getServer() == null
                ? -1 : event.getMaid().level().getServer().getTickCount());
        CiBridge.observe("product.reply", data);
    }

    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("name", event.getEntity().getName().getString());
        data.addProperty("uuid", event.getEntity().getUUID().toString());
        CiBridge.observe("player.join", data);
    }

    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("name", event.getEntity().getName().getString());
        data.addProperty("uuid", event.getEntity().getUUID().toString());
        CiBridge.observe("player.leave", data);
    }

    private static final class ServerPlatform implements CiBridge.CiPlatform {
        private final MinecraftServer server;

        private ServerPlatform(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public void execute(Runnable runnable) {
            server.execute(runnable);
        }

        @Override
        public long tick() {
            return server.getTickCount();
        }

        @Override
        public boolean inWorld() {
            return !server.isStopped();
        }

        @Override
        public String dist() {
            return "server";
        }

        @Override
        public JsonObject runAction(String op, JsonObject action) throws Exception {
            return CiServerDriver.execute(op, action, server);
        }
    }
}
