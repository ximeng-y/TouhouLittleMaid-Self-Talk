package com.maidmod.selftalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家自话/互聊设置网络包注册与共享处理工具（Forge 1.20.1 SimpleChannel）。
 * <p>
 * 由主类在无参构造器中直接调用 {@link #register()} 注册（与 TLM NetworkHandler 同构，
 * 注册先于任何包收发，无需等待事件时机）。
 */
public final class SelfTalkPackets {

    // v3：新增互聊三个消息，升版让不匹配版本在协商期被拒绝
    private static final String PROTOCOL_VERSION = "3";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MaidSelfTalkMod.MODID, "main"),
            () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);

    /** 每玩家每秒最多处理的设置包数（正常 UI 操作远低于此，仅防包风暴） */
    private static final int MAX_CONFIG_PACKETS_PER_SECOND = 20;
    /** 玩家 UUID -> [上次处理的秒, 该秒内处理数]（仅服务端主线程访问；玩家登出时随 removeRateEntry 清理） */
    private static final Map<UUID, long[]> PACKET_RATE = new HashMap<>();

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
        CHANNEL.registerMessage(nextId++, InterChatConfigRequestMessage.class,
                InterChatConfigRequestMessage::encode, InterChatConfigRequestMessage::decode,
                InterChatConfigRequestMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, InterChatConfigSetMessage.class,
                InterChatConfigSetMessage::encode, InterChatConfigSetMessage::decode,
                InterChatConfigSetMessage::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, InterChatConfigResponseMessage.class,
                InterChatConfigResponseMessage::encode, InterChatConfigResponseMessage::decode,
                InterChatConfigResponseMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 每玩家每秒限流：防恶意客户端包风暴（正常设置界面操作远低于该频率） */
    static boolean allowConfigPacket(UUID playerUuid) {
        long second = System.currentTimeMillis() / 1000;
        long[] entry = PACKET_RATE.get(playerUuid);
        if (entry == null) {
            PACKET_RATE.put(playerUuid, new long[]{second, 1});
            return true;
        }
        if (entry[0] != second) {
            entry[0] = second;
            entry[1] = 1;
            return true;
        }
        if (entry[1] >= MAX_CONFIG_PACKETS_PER_SECOND) {
            MaidSelfTalkMod.LOGGER.warn("Player {} exceeded config packet rate limit", playerUuid);
            return false;
        }
        entry[1]++;
        return true;
    }

    /** 玩家登出时清理限流条目，防长期多人服务端内存缓慢增长 */
    public static void removeRateEntry(UUID playerUuid) {
        PACKET_RATE.remove(playerUuid);
    }

    /**
     * 目标女仆是否存在且属于该玩家：客户端发送的是女仆实体 UUID，校验其主人是否为发送者本人。
     * 服务端只读主人自己的持久化设置，非本人的单只设置请求一律丢弃
     * （TLM 聊天界面本身无归属校验，任何玩家都可对任意女仆打开，故必须在此拦截）
     */
    static boolean isOwnedMaid(ServerPlayer player, UUID maidUuid) {
        if (!(player.serverLevel().getEntity(maidUuid) instanceof EntityMaid maid)) {
            return false;
        }
        return player.getUUID().equals(maid.getOwnerUUID());
    }
}
