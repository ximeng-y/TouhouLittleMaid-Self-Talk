package com.maidmod.selftalk.citest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maidmod.selftalk.citest.CiJson;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试 mod 与本机控制进程之间的唯一通道。
 * <p>
 * 控制进程通过 HTTP 下发动作（服务端／客户端），测试 mod 把每个动作调度到对应的游戏线程执行，
 * 再把结果与观测事件回传。本类不直接读写任何产品状态：动作执行全部委托给
 * {@link CiPlatform#runAction(String, JsonObject)}，由服务端／客户端各自的驱动实现。
 * <p>
 * 只在 {@code -Dci.controlUrl} 与 {@code -Dci.role} 同时存在时启用；生产客户端／服务端不设置这两个属性，
 * 测试 mod 也只在 ciRuntime 运行配置里加载。所有网络访问都有超时，控制进程消失不会卡住游戏线程。
 */
public final class CiBridge {

    private static final AtomicLong OUT_SEQ = new AtomicLong();
    private static final AtomicLong OBS_SEQ = new AtomicLong();
    private static final Set<String> SEEN_ACTIONS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<OutMessage> OUTBOX = new ConcurrentLinkedQueue<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static volatile boolean configured = false;
    private static volatile boolean running = false;
    private static volatile String runId = "";
    private static volatile String role = "";
    private static volatile String controlUrl = "";
    private static volatile String currentScenario = "system";
    private static volatile CiPlatform platform = null;
    private static volatile Thread pollThread;
    private static volatile Thread sendThread;
    private static volatile long lastHeartbeat = 0L;
    private static volatile long startedAt = 0L;

    private CiBridge() {
    }

    /** 读取系统属性；非测试运行返回 false，不创建线程也不发任何请求 */
    public static synchronized boolean init() {
        if (configured) {
            return true;
        }
        String url = System.getProperty("ci.controlUrl", "");
        String r = System.getProperty("ci.role", "");
        if (url.isBlank() || r.isBlank()) {
            return false;
        }
        controlUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        role = r;
        runId = System.getProperty("ci.runId", "local");
        configured = true;
        CiLog.info("control bridge configured role={} runId={} url={}", role, runId, controlUrl);
        return true;
    }

    public static boolean isConfigured() {
        return configured;
    }

    public static boolean isRunning() {
        return running;
    }

    public static String runId() {
        return runId;
    }

    public static String role() {
        return role;
    }

    /** 启动桥接：注册角色并开始轮询；重复调用无副作用 */
    public static synchronized void start(CiPlatform newPlatform) {
        if (!configured || running) {
            return;
        }
        platform = newPlatform;
        running = true;
        startedAt = System.currentTimeMillis();
        send(new OutMessage("register", registerBody()));
        pollThread = new Thread(CiBridge::pollLoop, "ci-control-poll-" + role);
        pollThread.setDaemon(true);
        pollThread.start();
        sendThread = new Thread(CiBridge::sendLoop, "ci-control-send-" + role);
        sendThread.setDaemon(true);
        sendThread.start();
        CiLog.info("control bridge started role={}", role);
    }

    /** 停止桥接并做一次 best-effort 退出通知；游戏进程退出前调用 */
    public static synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        JsonObject bye = new JsonObject();
        bye.addProperty("runId", runId);
        bye.addProperty("role", role);
        bye.addProperty("reason", "stopping");
        try {
            post("/api/bye", bye, 1500);
        } catch (Throwable ignored) {
            // 控制进程先退出时忽略
        }
        if (pollThread != null) {
            pollThread.interrupt();
        }
        if (sendThread != null) {
            sendThread.interrupt();
        }
        platform = null;
        CiLog.info("control bridge stopped role={}", role);
    }

    /** 把动作执行结果回传；status 只会是 ok / error */
    public static void result(String actionId, String scenarioId, String status, JsonObject payload,
                              String error) {
        JsonObject body = new JsonObject();
        body.addProperty("runId", runId);
        body.addProperty("role", role);
        body.addProperty("actionId", actionId);
        body.addProperty("scenarioId", scenarioId == null ? "" : scenarioId);
        body.addProperty("status", status);
        body.add("payload", payload == null ? new JsonObject() : payload);
        if (error != null) {
            body.addProperty("error", error);
        }
        send(new OutMessage("result", body));
    }

    /** 上报一条观测事件；事件按到达顺序编号，供报告还原现场 */
    public static void observe(String type, JsonObject data) {
        if (!configured) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("runId", runId);
        body.addProperty("role", role);
        body.addProperty("scenarioId", currentScenario);
        body.addProperty("type", type);
        body.addProperty("time", System.currentTimeMillis());
        body.addProperty("seq", OBS_SEQ.incrementAndGet());
        body.add("data", data == null ? new JsonObject() : data);
        send(new OutMessage("observation", body));
    }

    /** 当前场景只由最近一次动作切换；异步事件沿用最近场景，直到下一个动作 */
    static String currentScenario() {
        return currentScenario;
    }

    private static JsonObject registerBody() {
        JsonObject body = new JsonObject();
        body.addProperty("runId", runId);
        body.addProperty("role", role);
        body.addProperty("dist", platform == null ? "" : platform.dist());
        body.addProperty("mcVersion", "1.21.1");
        body.addProperty("mod", "maid_self_talk_ci");
        body.addProperty("pid", ProcessHandle.current().pid());
        body.addProperty("startedAt", startedAt);
        return body;
    }

    private static void send(OutMessage msg) {
        OUTBOX.add(msg);
    }

    private static void pollLoop() {
        while (running) {
            try {
                String query = "?runId=" + URLEncoder.encode(runId, StandardCharsets.UTF_8)
                        + "&role=" + URLEncoder.encode(role, StandardCharsets.UTF_8);
                HttpResponse<String> response = get("/api/actions" + query, 30);
                if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (root.has("action") && root.get("action").isJsonObject()) {
                        JsonObject action = root.getAsJsonObject("action");
                        acceptAction(action);
                    }
                }
                heartbeatIfDue();
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                if (running) {
                    CiLog.warn("control poll failed: {}", t.toString());
                    sleepQuietly(1000L);
                }
            }
        }
    }

    private static void heartbeatIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat < 2000L || platform == null) {
            return;
        }
        lastHeartbeat = now;
        JsonObject body = new JsonObject();
        body.addProperty("runId", runId);
        body.addProperty("role", role);
        body.addProperty("tick", platform.tick());
        body.addProperty("inWorld", platform.inWorld());
        body.addProperty("time", now);
        send(new OutMessage("heartbeat", body));
    }

    private static void acceptAction(JsonObject action) {
        String actionId = CiJson.str(action, "id");
        if (actionId.isBlank() || !SEEN_ACTIONS.add(actionId)) {
            return;
        }
        String id = CiJson.str(action, "id");
        String op = CiJson.str(action, "op");
        currentScenario = CiJson.str(action, "scenarioId");
        CiPlatform p = platform;
        if (p == null) {
            result(id, currentScenario, "error", null, "platform not installed");
            return;
        }
        try {
            p.execute(() -> {
                try {
                    JsonObject payload = p.runAction(op, action);
                    result(id, currentScenario, "ok", payload, null);
                } catch (Throwable t) {
                    CiLog.error("action failed op=" + op, t);
                    result(id, currentScenario, "error", null, t.getClass().getName() + ": " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            result(id, currentScenario, "error", null, "cannot schedule action: " + t);
        }
    }

    private static void sendLoop() {
        while (running || !OUTBOX.isEmpty()) {
            OutMessage msg = OUTBOX.poll();
            if (msg == null) {
                if (!running) {
                    return;
                }
                sleepQuietly(50L);
                continue;
            }
            String path = switch (msg.kind) {
                case "register" -> "/api/register";
                case "result" -> "/api/results";
                case "observation" -> "/api/observations";
                case "heartbeat" -> "/api/heartbeat";
                default -> "/api/events";
            };
            boolean sent = false;
            for (int attempt = 0; attempt < 3 && !sent; attempt++) {
                try {
                    HttpResponse<String> response = post(path, msg.body, 5000);
                    sent = response.statusCode() >= 200 && response.statusCode() < 300;
                } catch (Throwable t) {
                    if (attempt == 2) {
                        CiLog.warn("control send failed kind={} path={}: {}", msg.kind, path, t.toString());
                    } else {
                        sleepQuietly(200L);
                    }
                }
            }
            if (!sent && running) {
                CiLog.warn("drop control message kind={}", msg.kind);
            }
        }
    }

    private static HttpResponse<String> get(String path, int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(controlUrl + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(String path, JsonObject body, int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(controlUrl + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record OutMessage(String kind, JsonObject body) {
    }

    /** 角色平台：把动作调度到正确的线程，并暴露 tick／世界状态 */
    public interface CiPlatform {
        void execute(Runnable runnable);

        long tick();

        boolean inWorld();

        String dist();

        JsonObject runAction(String op, JsonObject action) throws Exception;
    }

    /** 供测试代码读取控制地址（mock 站点配置由 Python 控制进程注入，不在此处理） */
    public static Map<String, String> properties() {
        return Map.of("runId", runId, "role", role, "controlUrl", controlUrl);
    }
}
