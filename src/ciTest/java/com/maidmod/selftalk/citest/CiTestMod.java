package com.maidmod.selftalk.citest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * CI 2 专用测试 mod 入口。
 * <p>
 * 只有 ciRuntime 运行配置会加载本 mod，且只有控制进程显式传入 {@code -Dci.controlUrl} 与
 * {@code -Dci.role} 时才会启动控制通道；普通客户端／服务端即使误加载也不会连接任何地址。
 * <p>
 * 测试动作全部走真实游戏路径：服务端实体／事件／配置与客户端界面／网络包，不直接调用产品内部
 * 状态机的提交函数，也不在世界 NBT 中埋测试开关。
 */
@Mod(CiTestMod.MODID)
public final class CiTestMod {

    public static final String MODID = "maid_self_talk_ci";

    public CiTestMod(IEventBus modEventBus) {
        if (!CiBridge.init()) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(CiServerHooks::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CiServerHooks::onServerStopping);
        NeoForge.EVENT_BUS.addListener(CiServerHooks::onMaidReply);
        NeoForge.EVENT_BUS.addListener(CiServerHooks::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(CiServerHooks::onPlayerLeave);
        if (FMLEnvironment.dist.isClient()) {
            CiClientHooks.register();
        }
    }
}
