package com.maidmod.selftalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.PlayerSettingsStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：客户端提交玩家自话设置。
 *
 * @param maidUuid 目标女仆 UUID；为空表示设置"我的所有女仆"全局开关
 * @param enabled  是否触发自言自语
 */
public class SelfTalkConfigSetMessage {

    /** 每玩家每秒最多处理的设置包数（正常 UI 操作远低于此，仅防包风暴） */
    private static final int MAX_CONFIG_PACKETS_PER_SECOND = 20;
    /** 玩家 UUID -> [上次处理的秒, 该秒内处理数]（仅服务端主线程访问） */
    private static final Map<UUID, long[]> PACKET_RATE = new HashMap<>();

    private final Optional<UUID> maidUuid;
    private final boolean enabled;

    public SelfTalkConfigSetMessage(Optional<UUID> maidUuid, boolean enabled) {
        this.maidUuid = maidUuid;
        this.enabled = enabled;
    }

    public static void encode(SelfTalkConfigSetMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.maidUuid.isPresent());
        msg.maidUuid.ifPresent(uuid -> buf.writeUUID(uuid));
        buf.writeBoolean(msg.enabled);
    }

    public static SelfTalkConfigSetMessage decode(FriendlyByteBuf buf) {
        Optional<UUID> maidUuid = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
        return new SelfTalkConfigSetMessage(maidUuid, buf.readBoolean());
    }

    public static void handle(SelfTalkConfigSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null && allowConfigPacket(serverPlayer.getUUID())) {
                if (msg.maidUuid.isEmpty()) {
                    PlayerSettingsStorage.setEnabled(serverPlayer, msg.enabled);
                } else {
                    UUID maidUuid = msg.maidUuid.get();
                    // 只允许设置自己拥有的女仆：伪造 UUID 写入任意女仆会污染名单并撑爆玩家 NBT
                    //（设置界面只能对本人的女仆打开，正常玩家请求必然命中）
                    if (isOwnedMaid(serverPlayer, maidUuid)) {
                        PlayerSettingsStorage.setMaidDisabled(serverPlayer, maidUuid, !msg.enabled);
                    }
                }
            }
        });
        ctx.get().setPacketHandled(true);
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
            MaidSelfTalkMod.LOGGER.warn("Player {} exceeded self-talk config packet rate limit", playerUuid);
            return false;
        }
        entry[1]++;
        return true;
    }

    /**
     * 目标女仆是否存在且属于该玩家：客户端发送的是女仆实体 UUID，校验其主人是否为发送者本人。
     * 服务端只读主人自己的持久化设置，非本人的单只设置请求一律丢弃
     * （TLM 聊天界面本身无归属校验，任何玩家都可对任意女仆打开，故必须在此拦截）
     */
    private static boolean isOwnedMaid(ServerPlayer player, UUID maidUuid) {
        if (!(player.serverLevel().getEntity(maidUuid) instanceof EntityMaid maid)) {
            return false;
        }
        return player.getUUID().equals(maid.getOwnerUUID());
    }
}
