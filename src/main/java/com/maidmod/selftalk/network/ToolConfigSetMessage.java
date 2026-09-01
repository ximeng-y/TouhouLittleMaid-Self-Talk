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
 * C2S：客户端提交玩家 Tool 调用设置（结构同 InterChatConfigSetMessage）。
 *
 * @param maidUuid 目标女仆 UUID；为空表示设置"我的所有女仆"全局开关
 * @param enabled  是否开启 Tool 调用
 */
public class ToolConfigSetMessage {

    private final Optional<UUID> maidUuid;
    private final boolean enabled;

    public ToolConfigSetMessage(Optional<UUID> maidUuid, boolean enabled) {
        this.maidUuid = maidUuid;
        this.enabled = enabled;
    }

    public static void encode(ToolConfigSetMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.maidUuid.isPresent());
        msg.maidUuid.ifPresent(buf::writeUUID);
        buf.writeBoolean(msg.enabled);
    }

    public static ToolConfigSetMessage decode(FriendlyByteBuf buf) {
        Optional<UUID> maidUuid = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
        return new ToolConfigSetMessage(maidUuid, buf.readBoolean());
    }

    public static void handle(ToolConfigSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null && SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                if (msg.maidUuid.isEmpty()) {
                    PlayerSettingsStore.setToolCallGlobal(serverPlayer.server, serverPlayer.getUUID(), msg.enabled);
                } else {
                    UUID maidUuid = msg.maidUuid.get();
                    // 归属校验：仅允许主人操作自己拥有的女仆，防伪造 UUID 污染名单
                    if (!SelfTalkPackets.isOwnedMaid(serverPlayer, maidUuid)) {
                        return;
                    }
                    // 名单只存关闭项：关闭时新增条目，重新开启时移除，避免名单膨胀。
                    // 容量上限：新增键时校验，防恶意客户端伪造任意 UUID 无限撑大世界存档
                    if (!msg.enabled
                            && !PlayerSettingsStore.isToolCallMaidDisabled(serverPlayer.server, serverPlayer.getUUID(), maidUuid)
                            && PlayerSettingsStore.countToolCallMaidOverrides(serverPlayer.server, serverPlayer.getUUID()) >= 256) {
                        MaidSelfTalkMod.LOGGER.warn("Player {} tool-call maid override list full, ignored",
                                serverPlayer.getUUID());
                        return;
                    }
                    PlayerSettingsStore.setToolCallMaidDisabled(serverPlayer.server,
                            serverPlayer.getUUID(), maidUuid, !msg.enabled);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
