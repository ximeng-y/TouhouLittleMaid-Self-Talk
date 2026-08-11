package com.maidmod.selftalk.network;

import com.maidmod.selftalk.PlayerSettingsStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

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
            if (serverPlayer != null) {
                if (msg.maidUuid.isEmpty()) {
                    PlayerSettingsStorage.setEnabled(serverPlayer, msg.enabled);
                } else {
                    PlayerSettingsStorage.setMaidDisabled(serverPlayer, msg.maidUuid.get(), !msg.enabled);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
