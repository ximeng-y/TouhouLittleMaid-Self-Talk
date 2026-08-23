package com.maidmod.selftalk.network;

import com.maidmod.selftalk.PlayerSettingsStorage;
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
                    PlayerSettingsStorage.setInterChatEnabled(serverPlayer, msg.enabled);
                } else {
                    UUID maidUuid = msg.maidUuid.get();
                    // 只允许设置自己拥有的女仆：伪造 UUID 写入任意女仆会污染名单并撑爆玩家 NBT
                    //（设置界面只能对本人的女仆打开，正常玩家请求必然命中）
                    if (SelfTalkPackets.isOwnedMaid(serverPlayer, maidUuid)) {
                        PlayerSettingsStorage.setInterChatMaidDisabled(serverPlayer, maidUuid, !msg.enabled);
                    }
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
