package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 玩家自话设置网络包注册（Forge 1.20.1 SimpleChannel）。
 * <p>
 * 由主类在无参构造器中直接调用 {@link #register()} 注册（与 TLM NetworkHandler 同构，
 * 注册先于任何包收发，无需等待事件时机）。
 */
public final class SelfTalkPackets {

    // v2：玩家设置两级化后三个消息布局均变更，升版让不匹配版本在协商期被拒绝
    private static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MaidSelfTalkMod.MODID, "main"),
            () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private SelfTalkPackets() {
    }

    public static void register() {
        CHANNEL.registerMessage(nextId++, SelfTalkConfigRequestMessage.class,
                SelfTalkConfigRequestMessage::encode, SelfTalkConfigRequestMessage::decode,
                SelfTalkConfigRequestMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, SelfTalkConfigSetMessage.class,
                SelfTalkConfigSetMessage::encode, SelfTalkConfigSetMessage::decode,
                SelfTalkConfigSetMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, SelfTalkConfigResponseMessage.class,
                SelfTalkConfigResponseMessage::encode, SelfTalkConfigResponseMessage::decode,
                SelfTalkConfigResponseMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
