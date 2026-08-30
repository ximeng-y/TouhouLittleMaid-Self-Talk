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
 * C2S：客户端提交玩家自话设置。
 * <p>
 * 限流与归属校验用 {@link SelfTalkPackets} 的共享工具（与互聊设置包同构）。
 *
 * @param maidUuid 目标女仆 UUID；为空表示设置"我的所有女仆"全局开关
 * @param enabled  是否触发自言自语
 */
public class SelfTalkConfigSetMessage {

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
            if (serverPlayer != null && SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                if (msg.maidUuid.isEmpty()) {
                    PlayerSettingsStore.setSelfTalkEnabled(serverPlayer.server, serverPlayer.getUUID(), msg.enabled);
                } else {
                    UUID maidUuid = msg.maidUuid.get();
                    // 只允许设置自己拥有的女仆：伪造 UUID 写入任意女仆会污染名单并撑爆世界存档
                    //（设置界面只能对本人的女仆打开，正常玩家请求必然命中）
                    if (SelfTalkPackets.isOwnedMaid(serverPlayer, maidUuid)
                            && (msg.enabled || PlayerSettingsStore.isSelfTalkMaidDisabled(
                            serverPlayer.server, serverPlayer.getUUID(), maidUuid)
                            || PlayerSettingsStore.countSelfTalkMaidOverrides(
                            serverPlayer.server, serverPlayer.getUUID()) < 256)) {
                        PlayerSettingsStore.setSelfTalkMaidDisabled(serverPlayer.server,
                                serverPlayer.getUUID(), maidUuid, !msg.enabled);
                    } else if (!msg.enabled) {
                        MaidSelfTalkMod.LOGGER.warn("Player {} self-talk maid override list full, ignored",
                                serverPlayer.getUUID());
                    }
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
