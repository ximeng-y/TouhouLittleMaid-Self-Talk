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
 * C2S：客户端提交玩家互聊设置。
 *
 * @param maidUuid 目标女仆 UUID；为空表示设置"我的所有女仆"全局开关
 * @param enabled  是否触发互聊
 */
public class InterChatConfigSetMessage {

    private final Optional<UUID> maidUuid;
    private final boolean enabled;

    public InterChatConfigSetMessage(Optional<UUID> maidUuid, boolean enabled) {
        this.maidUuid = maidUuid;
        this.enabled = enabled;
    }

    public static void encode(InterChatConfigSetMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.maidUuid.isPresent());
        msg.maidUuid.ifPresent(uuid -> buf.writeUUID(uuid));
        buf.writeBoolean(msg.enabled);
    }

    public static InterChatConfigSetMessage decode(FriendlyByteBuf buf) {
        Optional<UUID> maidUuid = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
        return new InterChatConfigSetMessage(maidUuid, buf.readBoolean());
    }

    public static void handle(InterChatConfigSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null && SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                if (msg.maidUuid.isEmpty()) {
                    PlayerSettingsStore.setInterChatEnabled(serverPlayer.server, serverPlayer.getUUID(), msg.enabled);
                } else {
                    UUID maidUuid = msg.maidUuid.get();
                    // 只允许设置自己拥有的女仆：伪造 UUID 写入任意女仆会污染名单并撑爆世界存档
                    //（设置界面只能对本人的女仆打开，正常玩家请求必然命中）
                    // 归属校验：仅允许主人操作自己拥有的女仆，防伪造 UUID 污染名单
                    if (!SelfTalkPackets.isOwnedMaid(serverPlayer, maidUuid)) {
                        return;
                    }
                    // 名单只存关闭项：关闭时新增条目，重新开启时移除，避免名单膨胀。
                    // 容量上限：新增键时校验，防恶意客户端伪造任意 UUID 无限撑大世界存档
                    if (!msg.enabled
                            && !PlayerSettingsStore.isInterChatMaidDisabled(serverPlayer.server, serverPlayer.getUUID(), maidUuid)
                            && PlayerSettingsStore.countInterChatMaidOverrides(serverPlayer.server, serverPlayer.getUUID()) >= 256) {
                        MaidSelfTalkMod.LOGGER.warn("Player {} inter-chat maid override list full ({}), ignored",
                                serverPlayer.getUUID(), 256);
                        return;
                    }
                    PlayerSettingsStore.setInterChatMaidDisabled(serverPlayer.server,
                            serverPlayer.getUUID(), maidUuid, !msg.enabled);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
