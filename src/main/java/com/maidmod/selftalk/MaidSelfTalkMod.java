package com.maidmod.selftalk;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.maidmod.selftalk.network.SelfTalkPackets;

/**
 * 女仆 AI 自言自语与互相对话（重写版）入口。
 */
@Mod(MaidSelfTalkMod.MODID)
public class MaidSelfTalkMod {

    public static final String MODID = "maid_self_talk";
    public static final Logger LOGGER = LoggerFactory.getLogger(MaidSelfTalkMod.class);

    public MaidSelfTalkMod() {
        // Forge 1.20.1 FMLModContainer 只支持无参构造器
        // COMMON 配置（服务端权威）
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // 网络包（SimpleChannel 注册无需事件时机，构造期直接注册，与 TLM NetworkHandler 同构）
        SelfTalkPackets.register();
        // 服务端状态机
        MinecraftForge.EVENT_BUS.register(SelfTalkHandler.class);

        // 客户端 Cloth Config 配置界面：通过反射注册（字节码不直接引用 cloth 类，
        // 未安装 cloth-config 时自动跳过，不影响模组核心功能）
        if (FMLEnvironment.dist.isClient()) {
            registerClothConfigIfPresent();
        }
    }

    /**
     * Cloth Config 全局配置界面（挂到 TLM 的 AI 全局设置分类）。
     * <p>
     * 引用 cloth 客户端类的订阅器类不能在未装 cloth-config 时被加载，
     * 因此不用 @EventBusSubscriber 自动注册，而是：
     * 1. 仅在客户端、且 ModList 中存在 cloth_config 时；
     * 2. 通过 Class.forName + 反射注册（字节码中不出现对 cloth/订阅器类的直接引用）。
     */
    private static void registerClothConfigIfPresent() {
        try {
            if (ModList.get().isLoaded("cloth_config")) {
                Class<?> clazz = Class.forName("com.maidmod.selftalk.client.SelfTalkClothConfig");
                MinecraftForge.EVENT_BUS.register(clazz);
            }
        } catch (Exception | LinkageError e) {
            // 反射失败仅影响配置界面，不影响模组核心功能
            LOGGER.warn("Failed to register SelfTalkClothConfig, cloth config UI disabled", e);
        }
    }
}
