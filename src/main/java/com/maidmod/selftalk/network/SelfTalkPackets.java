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
 * 由主类通过 {@code modEventBus.addListener(SelfTalkPackets::register)} 手动注册。
 */
public final class SelfTalkPackets {

    private static final String PROTOCOL_VERSION = "1";

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
