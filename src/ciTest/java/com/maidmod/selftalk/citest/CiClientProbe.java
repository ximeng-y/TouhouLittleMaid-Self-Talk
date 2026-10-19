package com.maidmod.selftalk.citest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 客户端观测缓冲区：网络线程写入，游戏线程读取；只保留最近一段，避免长跑内存膨胀 */
public final class CiClientProbe {

    private static final int MAX = 200;
    private static final Deque<JsonObject> CHAT = new ArrayDeque<>();
    private static final Deque<JsonObject> TTS = new ArrayDeque<>();
    private static final List<String> RENDERED_WAITING = new CopyOnWriteArrayList<>();
    private static final List<String> RENDERED_BUBBLES = new CopyOnWriteArrayList<>();

    private CiClientProbe() {
    }

    public static synchronized void chat(String text, String source) {
        JsonObject e = new JsonObject();
        e.addProperty("text", text);
        e.addProperty("source", source);
        e.addProperty("time", System.currentTimeMillis());
        push(CHAT, e);
    }

    public static synchronized void tts(int maidId, byte[] data) {
        JsonObject e = new JsonObject();
        e.addProperty("maidId", maidId);
        e.addProperty("bytes", data == null ? 0 : data.length);
        e.addProperty("head", data == null || data.length == 0 ? "" : firstBytes(data));
        e.addProperty("time", System.currentTimeMillis());
        push(TTS, e);
    }

    public static void renderedBubble(String rendererClass) {
        RENDERED_BUBBLES.add(rendererClass);
        while (RENDERED_BUBBLES.size() > MAX) {
            RENDERED_BUBBLES.remove(0);
        }
        if (rendererClass.contains("WaitingChatBubbleRenderer")) {
            RENDERED_WAITING.add(rendererClass);
            while (RENDERED_WAITING.size() > MAX) {
                RENDERED_WAITING.remove(0);
            }
        }
    }

    public static synchronized JsonArray chatSnapshot() {
        JsonArray out = new JsonArray();
        CHAT.forEach(out::add);
        return out;
    }

    public static synchronized JsonArray ttsSnapshot() {
        JsonArray out = new JsonArray();
        TTS.forEach(out::add);
        return out;
    }

    public static int waitingRenderCount() {
        return RENDERED_WAITING.size();
    }

    public static int bubbleRenderCount() {
        return RENDERED_BUBBLES.size();
    }

    public static synchronized void clear() {
        CHAT.clear();
        TTS.clear();
        RENDERED_WAITING.clear();
        RENDERED_BUBBLES.clear();
    }

    private static void push(Deque<JsonObject> deque, JsonObject value) {
        deque.addLast(value);
        while (deque.size() > MAX) {
            deque.removeFirst();
        }
    }

    private static String firstBytes(byte[] data) {
        int n = Math.min(8, data.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", data[i]));
        }
        return sb.toString();
    }
}
