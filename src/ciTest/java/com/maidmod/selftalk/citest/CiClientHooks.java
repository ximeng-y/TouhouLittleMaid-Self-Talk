package com.maidmod.selftalk.citest;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** 客户端事件钩子：进入世界后才启动桥接，退出时停止 */
public final class CiClientHooks {

    private CiClientHooks() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CiClientHooks::onClientTick);
        NeoForge.EVENT_BUS.addListener(CiClientHooks::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(CiClientHooks::onLoggingOut);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!CiBridge.isRunning() && mc.player != null && mc.level != null) {
            CiBridge.start(new ClientPlatform(mc));
        }
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        JsonObject data = new JsonObject();
        data.addProperty("name", event.getPlayer().getName().getString());
        data.addProperty("uuid", event.getPlayer().getUUID().toString());
        CiBridge.observe("client.logging_in", data);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        JsonObject data = new JsonObject();
        if (event.getPlayer() != null) {
            data.addProperty("name", event.getPlayer().getName().getString());
        }
        CiBridge.observe("client.logging_out", data);
    }

    private static final class ClientPlatform implements CiBridge.CiPlatform {
        private final Minecraft mc;

        private ClientPlatform(Minecraft mc) {
            this.mc = mc;
        }

        @Override
        public void execute(Runnable runnable) {
            mc.execute(runnable);
        }

        @Override
        public long tick() {
            return mc.level == null ? 0L : mc.level.getGameTime();
        }

        @Override
        public boolean inWorld() {
            return mc.player != null && mc.level != null;
        }

        @Override
        public String dist() {
            return "client";
        }

        @Override
        public JsonObject runAction(String op, JsonObject action) {
            return CiClientDriver.execute(op, action, mc);
        }
    }
}
