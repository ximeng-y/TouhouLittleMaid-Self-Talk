package com.maidmod.selftalk.citest;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.google.gson.JsonObject;
import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.MaidPersonaMode;
import com.maidmod.selftalk.MaidPersonaRuntime;
import com.maidmod.selftalk.SelfTalkCallback;
import com.maidmod.selftalk.SelfTalkState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CI 2 的故障注入与真实回调回放入口。
 * <p>
 * 本类只被测试 mod 的 {@code server.fault} 动作调用，不改变正式配置和产品 API。
 * 回放对象来自 {@link SelfTalkState.State#currentSelfTalkCallback} 或
 * {@link CiFaultSelfTalkCallbackMixin} 捕获的真实回调，不构造伪造的 HTTP 双回调。
 */
public final class CiFaults {

    private static final Map<Integer, SelfTalkCallback> CAPTURED = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> TTS_THROW_REMAINING = new ConcurrentHashMap<>();

    private CiFaults() {
    }

    /**
     * 由 server.fault dispatch 调用。所有分支都在服务端主线程执行。
     */
    public static JsonObject execute(MinecraftServer server, JsonObject action) {
        String fault = CiJson.str(action, "fault", "capture");
        return switch (fault) {
            case "capture" -> capture(server, action);
            case "replay_success" -> replaySuccess(server, action);
            case "replay_failure" -> replayFailure(server, action);
            case "age_pending" -> agePending(server, action);
            case "arm_tts_throw" -> armTtsThrow(action);
            case "persona_change" -> personaChange(server, action);
            case "reset" -> reset();
            default -> throw new IllegalArgumentException("unknown CI fault: " + fault);
        };
    }

    /** 由 SelfTalkCallback 构造 Mixin 提前登记，便于动作到达时直接回放该实例。 */
    public static void captureConstructed(SelfTalkCallback callback) {
        if (callback == null || callback.getMaid() == null) {
            return;
        }
        CAPTURED.put(callback.getMaid().getId(), callback);
    }

    /** 由 TTS Mixin 消费；每次只消费一枚故障令牌，避免污染后续请求。 */
    public static boolean consumeSynchronousTtsThrow(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        int maidId = maid.getId();
        for (;;) {
            Integer remaining = TTS_THROW_REMAINING.get(maidId);
            if (remaining == null) {
                return false;
            }
            if (remaining <= 1) {
                if (TTS_THROW_REMAINING.remove(maidId, remaining)) {
                    return true;
                }
            } else if (TTS_THROW_REMAINING.replace(maidId, remaining, remaining - 1)) {
                return true;
            }
        }
    }

    private static JsonObject capture(MinecraftServer server, JsonObject action) {
        int maidId = maidId(action);
        SelfTalkState.State state = SelfTalkState.peek(maidId);
        SelfTalkCallback callback = state == null ? null : state.currentSelfTalkCallback;
        if (callback == null) {
            callback = CAPTURED.get(maidId);
        }
        if (callback == null) {
            throw new IllegalStateException("no real self-talk callback for maid " + maidId);
        }
        CAPTURED.put(maidId, callback);
        JsonObject out = result(maidId, callback);
        out.addProperty("tick", server.getTickCount());
        return out;
    }

    private static JsonObject replaySuccess(MinecraftServer server, JsonObject action) {
        int maidId = maidId(action);
        SelfTalkCallback callback = callbackFor(server, maidId, action);
        String chat = CiJson.str(action, "chat", "CI 回放回答。");
        String tts = CiJson.str(action, "tts", chat);
        int repeat = Math.max(1, CiJson.integer(action, "repeat", 1));
        Throwable thrown = null;
        for (int i = 0; i < repeat; i++) {
            try {
                callback.onSuccess(new ResponseChat(chat, tts));
            } catch (Throwable error) {
                // 故障动作本身要把同步 TTS 抛错作为证据返回，不能让控制桥吞掉现场。
                thrown = error;
            }
        }
        JsonObject out = result(maidId, callback);
        out.addProperty("tick", server.getTickCount());
        out.addProperty("repeat", repeat);
        if (thrown != null) {
            out.addProperty("threw", true);
            out.addProperty("throwType", thrown.getClass().getName());
            out.addProperty("throwMessage", String.valueOf(thrown.getMessage()));
        } else {
            out.addProperty("threw", false);
        }
        return out;
    }

    private static JsonObject replayFailure(MinecraftServer server, JsonObject action) {
        int maidId = maidId(action);
        SelfTalkCallback callback = callbackFor(server, maidId, action);
        int errorCode = CiJson.integer(action, "errorCode", 500);
        String message = CiJson.str(action, "message", "CI injected failure");
        callback.onFailure(null, new RuntimeException(message), errorCode);
        JsonObject out = result(maidId, callback);
        out.addProperty("tick", server.getTickCount());
        out.addProperty("errorCode", errorCode);
        return out;
    }

    /**
     * 将本次真实请求的起始 tick 老化到产品超时阈值之后，再交给下一次真实 maid tick 判定。
     * 不直接调用产品超时分支，也不修改全局计时配置。
     */
    private static JsonObject agePending(MinecraftServer server, JsonObject action) {
        int maidId = maidId(action);
        SelfTalkState.State state = SelfTalkState.peek(maidId);
        if (state == null || state.currentSelfTalkCallback == null) {
            throw new IllegalStateException("no pending self-talk state for maid " + maidId);
        }
        CAPTURED.put(maidId, state.currentSelfTalkCallback);
        long ageTicks = Math.max(6001L, CiJson.integer(action, "ageTicks", 6001));
        state.selfTalkPending = true;
        state.selfTalkPendingSinceTick = server.getTickCount() - ageTicks;
        JsonObject out = result(maidId, state.currentSelfTalkCallback);
        out.addProperty("tick", server.getTickCount());
        out.addProperty("ageTicks", ageTicks);
        return out;
    }

    private static JsonObject armTtsThrow(JsonObject action) {
        int maidId = maidId(action);
        int count = Math.max(1, CiJson.integer(action, "count", 1));
        TTS_THROW_REMAINING.put(maidId, count);
        JsonObject out = new JsonObject();
        out.addProperty("maidId", maidId);
        out.addProperty("count", count);
        out.addProperty("armed", true);
        return out;
    }

    /**
     * 测试人设保存后的取消入口：先替换真实女仆的人设，再走产品统一配置变更清理。
     * 该入口主要用于取消在途历史/人设规划，不伪造正式自话回调。
     */
    private static JsonObject personaChange(MinecraftServer server, JsonObject action) {
        int maidId = maidId(action);
        EntityMaid maid = findMaid(server, maidId);
        if (maid == null) {
            throw new IllegalArgumentException("maid not found: " + maidId);
        }
        if (action.has("personaStructured")) {
            JsonObject persona = CiJson.obj(action, "personaStructured");
            MaidPersonaDraft draft = new MaidPersonaDraft(
                    CiJson.str(persona, "identity"),
                    CiJson.str(persona, "personality"),
                    CiJson.str(persona, "background"),
                    CiJson.str(persona, "hobbies"));
            MaidPersonaHost.replace(maid, new MaidPersonaConfig(MaidPersonaMode.STRUCTURED, draft));
        }
        MaidPersonaRuntime.onConfigChanged(maid);
        JsonObject out = new JsonObject();
        out.addProperty("maidId", maidId);
        out.addProperty("changed", true);
        return out;
    }

    private static SelfTalkCallback callbackFor(MinecraftServer server, int maidId, JsonObject action) {
        SelfTalkCallback callback = CAPTURED.get(maidId);
        if (callback == null || CiJson.bool(action, "recapture", true)) {
            SelfTalkState.State state = SelfTalkState.peek(maidId);
            if (state != null && state.currentSelfTalkCallback != null) {
                callback = state.currentSelfTalkCallback;
                CAPTURED.put(maidId, callback);
            }
        }
        if (callback == null) {
            throw new IllegalStateException("no captured self-talk callback for maid " + maidId);
        }
        return callback;
    }

    private static JsonObject result(int maidId, SelfTalkCallback callback) {
        JsonObject out = new JsonObject();
        out.addProperty("maidId", maidId);
        out.addProperty("captured", callback != null);
        out.addProperty("callbackMaidId", callback == null || callback.getMaid() == null
                ? -1 : callback.getMaid().getId());
        return out;
    }

    private static int maidId(JsonObject action) {
        int maidId = CiJson.integer(action, "maidId", -1);
        if (maidId < 0) {
            throw new IllegalArgumentException("maidId is required");
        }
        return maidId;
    }

    private static EntityMaid findMaid(MinecraftServer server, int maidId) {
        for (ServerLevel level : server.getAllLevels()) {
            var entity = level.getEntity(maidId);
            if (entity instanceof EntityMaid maid) {
                return maid;
            }
        }
        return null;
    }

    private static JsonObject reset() {
        CAPTURED.clear();
        TTS_THROW_REMAINING.clear();
        JsonObject out = new JsonObject();
        out.addProperty("reset", true);
        return out;
    }
}
