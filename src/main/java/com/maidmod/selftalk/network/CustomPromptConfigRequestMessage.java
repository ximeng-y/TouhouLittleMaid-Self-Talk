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
 * C2S：客户端请求当前玩家自定义 Prompt 设置（含管理员是否允许玩家配置）。
 *
 * @param maidUuid 打开设置界面的女仆 UUID，服务端据此返回该女仆的单只 Prompt
 */
public class CustomPromptConfigRequestMessage {

    private final UUID maidUuid;

    public CustomPromptConfigRequestMessage(UUID maidUuid) {
        this.maidUuid = maidUuid;
    }

    public static void encode(CustomPromptConfigRequestMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.maidUuid);
    }

    public static CustomPromptConfigRequestMessage decode(FriendlyByteBuf buf) {
        return new CustomPromptConfigRequestMessage(buf.readUUID());
    }

    /**
     * 返回的是存档原文（未清洗）；清洗只在构造请求注入时做，界面须原样回显玩家输入。
     */
    public static void handle(CustomPromptConfigRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer != null && msg.maidUuid != null
                    && SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                boolean adminEnabled = Config.PLAYER_OPTION_ENABLED.get();
                String globalPrompt = PlayerSettingsStore.getCustomPromptGlobal(
                        serverPlayer.server, serverPlayer.getUUID());
                String maidPrompt = PlayerSettingsStore.getCustomPromptForMaid(
                        serverPlayer.server, serverPlayer.getUUID(), msg.maidUuid);
                boolean overrideEnabled = PlayerSettingsStore.isCustomPromptOverrideEnabled(
                        serverPlayer.server, serverPlayer.getUUID());
                SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new CustomPromptConfigResponseMessage(adminEnabled, globalPrompt, maidPrompt, overrideEnabled));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
