package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Agent-Tweaks 对话生命周期门面 {@code com.xm2nd.tlmagenttweaks.api.LlmConversationLifecycle}
 * 的反射桥：静默作废一次 LLM 对话（取消 HTTP/排队/重试/deadline，并把迟到结果挡在
 * Agent-Tweaks 的公共响应调用点）。
 * <p>
 * 规则（对应计划的外部契约）：
 * <ul>
 *   <li><b>无编译依赖</b>：全部经 Java 原生反射访问，本 mod 不引入 Agent-Tweaks 依赖；
 *       Mod 未安装时桥为无操作，不影响任何互聊功能；</li>
 *   <li>Mod 已安装：按契约要求类与 {@code cancel} 方法存在；</li>
 *   <li><b>缺接口 / 调用失败 → 明确记录集成错误</b>，绝不把「接口缺失」当作「Mod 未安装」静默吞掉——
 *       集成错误与无操作的区分是上线排障的关键（缺桥的取消会退化为「只清本地资源、不取消上游」）；</li>
 *   <li>桥接异常一律吞下并记录：不得阻止本地资源收尾，也不得冒泡打断主人的新聊天。</li>
 * </ul>
 * 本桥只做「静默作废」：{@code cancel} 幂等、不调用业务回调；正常走成功/失败回调的请求不需要桥。
 * 版本兼容（方法签名在哪个 Agent-Tweaks 版本可用）属于 Agent-Tweaks 侧的职责，本 mod 不做旧版分支。
 */
public final class AgentTweaksLifecycleBridge {

    private AgentTweaksLifecycleBridge() {
    }

    /** 门面类全限定名（与 Agent-Tweaks 的公开 API 包名一致） */
    private static final String FACADE_CLASS = "com.xm2nd.tlmagenttweaks.api.LlmConversationLifecycle";
    /** 方法名：静默作废单个回调（幂等，参数为 TLM 的 LLMCallback） */
    private static final String CANCEL_METHOD = "cancel";
    /** 方法名：通知上游「一次对话已正常走完业务终态」（幂等，用于链自然结束时对齐生命周期记账） */
    private static final String COMPLETE_METHOD = "complete";

    /** 是否已探测过门面（探测结果只确定一次：Mod 是否安装不随运行期改变） */
    private static volatile boolean probed;
    /** Mod 已安装且接口完整：方法可用 */
    private static volatile boolean bridgeAvailable;
    /** 已记录的集成错误（只记录一次，避免每个互聊请求重复刷日志） */
    private static volatile boolean integrationErrorLogged;

    private static Method cancelMethod;
    private static Method completeMethod;

    /**
     * 静默作废一次互聊正式对话。
     * <p>
     * 语义：请求被业务判定作废（如互聊被主人插话）、且不再期待任何结果时调用。
     * 幂等；不调用业务回调；不代替业务清理工具历史、pending、等待气泡或互聊链。
     * <p>
     * Mod 未安装 → 无操作；Mod 已安装但接口缺失/调用失败 → 记录集成错误（只记一次）。
     * 任何异常都被吞掉：不阻止调用方继续本地资源收尾。
     *
     * @param callback 要作废的正式回调；{@code null} 为无操作
     */
    public static void cancel(LLMCallback callback) {
        if (callback == null) {
            return;
        }
        ensureProbed();
        if (!bridgeAvailable) {
            return;
        }
        try {
            cancelMethod.invoke(null, callback);
        } catch (Throwable t) {
            logIntegrationError(t);
        }
    }

    /**
     * 通知上游：一次互聊对话已在本 mod 侧正常走完业务终态。
     * <p>
     * 语义：请求已产生最终输出或已按失败正常收敛，需要 Agent-Tweaks 释放为该回调追踪的
     * 生命周期记账/队列资源；与 {@code cancel} 相对，都是幂等、不调用业务回调。
     * 仅作废用 {@link #cancel}：迟到结果不管走 {@code cancel} 还是 {@code complete} 都被挡在
     * 公共响应调用点之外（是否区分是本 mod 的选择，不影响正确性——这里明确只在链自然结束时
     * 调用 {@code complete}，其余一律 {@code cancel}，避免误把已作废的请求标成「正常完成」）。
     *
     * @param callback 已走完业务终态的回调；{@code null} 为无操作
     */
    public static void complete(LLMCallback callback) {
        if (callback == null) {
            return;
        }
        ensureProbed();
        if (!bridgeAvailable) {
            return;
        }
        try {
            completeMethod.invoke(null, callback);
        } catch (Throwable t) {
            logIntegrationError(t);
        }
    }

    /** 探测门面类与方法（只探测一次；线程竞争无害——最坏重复探测，结果一致） */
    private static void ensureProbed() {
        if (probed) {
            return;
        }
        try {
            Class<?> facade = Class.forName(FACADE_CLASS);
            cancelMethod = facade.getMethod(CANCEL_METHOD, LLMCallback.class);
            completeMethod = facade.getMethod(COMPLETE_METHOD, LLMCallback.class);
            if (!Modifier.isStatic(cancelMethod.getModifiers()) || !Modifier.isStatic(completeMethod.getModifiers())) {
                cancelMethod = null;
                completeMethod = null;
                logIntegrationError(new IllegalStateException(
                        FACADE_CLASS + "." + CANCEL_METHOD + "/" + COMPLETE_METHOD + " must be static"));
                return;
            }
            bridgeAvailable = true;
        } catch (ClassNotFoundException e) {
            // Mod 未安装：无操作，属预期状态，不记集成错误
            bridgeAvailable = false;
        } catch (NoSuchMethodException | SecurityException e) {
            logIntegrationError(e);
        } finally {
            probed = true;
        }
    }

    /** 记录集成错误（只记录一次）：桥不可用时取消会退化为「只清本地资源、不取消上游」 */
    private static void logIntegrationError(Throwable t) {
        if (integrationErrorLogged) {
            return;
        }
        integrationErrorLogged = true;
        MaidSelfTalkMod.LOGGER.error(
                "Agent-Tweaks lifecycle bridge integration error ({}): inter-chat cancellation "
                        + "degrades to local-only cleanup; update Agent-Tweaks to a compatible version",
                FACADE_CLASS, t);
    }
}