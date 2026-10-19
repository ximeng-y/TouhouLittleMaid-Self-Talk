package com.maidmod.selftalk.citest;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** 统一日志前缀，CI 日志按 {@code [ci]} 过滤即可还原测试侧事件 */
public final class CiLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CiLog() {
    }

    public static void info(String message, Object... args) {
        LOGGER.info("[ci] " + message, args);
    }

    public static void warn(String message, Object... args) {
        LOGGER.warn("[ci] " + message, args);
    }

    public static void error(String message, Throwable t) {
        LOGGER.error("[ci] " + message, t);
    }

    /** 故障注入必须显式标记：只由测试 mod 的显式动作调用，不由世界数据或玩家名推断 */
    public static void fault(String name, String detail) {
        LOGGER.warn("[ci][fault] {} {}", name, detail == null ? "" : detail);
    }
}
