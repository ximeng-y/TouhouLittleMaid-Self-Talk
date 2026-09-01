package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：客户端请求当前玩家 Tool 调用设置。
 *
 * @param maidUuid 打开设置界面的女仆 UUID，服务端据此返回该女仆的单只有效值
 */
public class ToolConfigRequestMessage {

    private final UUID maidUuid;

    public ToolConfigRequestMessage(UUID maidUuid) {
        this.maidUuid = maidUuid;
    }

    public static void encode(ToolConfigRequestMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidUuid);
    }

    public static ToolConfigRequestMessage decode(FriendlyByteBuf buf) {
        return new ToolConfigRequestMessage(buf.readUUID());
    }

    /** 两个管理员字段分开返回，界面提示语可区分关闸原因 */
    public static void handle(ToolConfigRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null && msg.maidUuid != null
                    && SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                boolean toolAdminEnabled = Config.TOOL_CALL_ENABLED.get();
                boolean globalEnabled = PlayerSettingsStore.isToolCallGlobal(serverPlayer.server, serverPlayer.getUUID());
                boolean maidEnabled = globalEnabled
                        && !PlayerSettingsStore.isToolCallMaidDisabled(serverPlayer.server, serverPlayer.getUUID(), msg.maidUuid);
                SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new ToolConfigResponseMessage(adminEnabled, toolAdminEnabled, globalEnabled, maidEnabled));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
