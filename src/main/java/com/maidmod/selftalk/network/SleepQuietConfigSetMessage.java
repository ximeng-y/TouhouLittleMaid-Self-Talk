package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：客户端提交玩家「睡觉时安静」设置。
 *
 * @param maidUuid 目标女仆 UUID；为空表示设置"我的所有女仆"全局开关
 * @param enabled  true = 睡觉时安静（不说话），false = 睡觉时说话
 */
public class SleepQuietConfigSetMessage {

    private final Optional<UUID> maidUuid;
    private final boolean enabled;

    public SleepQuietConfigSetMessage(Optional<UUID> maidUuid, boolean enabled) {
        this.maidUuid = maidUuid;
        this.enabled = enabled;
    }

    public static void encode(SleepQuietConfigSetMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.maidUuid.isPresent());
        msg.maidUuid.ifPresent(uuid -> buf.writeUUID(uuid));
        buf.writeBoolean(msg.enabled);
    }

    public static SleepQuietConfigSetMessage decode(FriendlyByteBuf buf) {
        Optional<UUID> maidUuid = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
        return new SleepQuietConfigSetMessage(maidUuid, buf.readBoolean());
    }

    public static void handle(SleepQuietConfigSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null && SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                if (msg.maidUuid.isEmpty()) {
                    PlayerSettingsStore.setSleepQuietGlobal(serverPlayer.server, serverPlayer.getUUID(), msg.enabled);
                } else {
                    // 归属校验：仅允许主人操作自己拥有的女仆，防伪造 UUID 污染名单
                    if (!SelfTalkPackets.isOwnedMaid(serverPlayer, msg.maidUuid.get())) {
                        return;
                    }
                    // 新增安静条目时校验容量上限（防恶意客户端伪造任意 UUID 无限撑大世界存档）
                    if (msg.enabled && !PlayerSettingsStore.isSleepQuietMaid(
                            serverPlayer.server, serverPlayer.getUUID(), msg.maidUuid.get())
                            && PlayerSettingsStore.countSleepQuietMaidOverrides(
                            serverPlayer.server, serverPlayer.getUUID()) >= 256) {
                        MaidSelfTalkMod.LOGGER.warn("Player {} sleep-quiet maid override list full, ignored",
                                serverPlayer.getUUID());
                        return;
                    }
                    PlayerSettingsStore.setSleepQuietMaid(serverPlayer.server,
                            serverPlayer.getUUID(), msg.maidUuid.get(), msg.enabled);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
