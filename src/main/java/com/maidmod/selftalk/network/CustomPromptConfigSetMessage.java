package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：客户端提交自定义 Prompt 设置。一个包三种写入意图，服务端做互斥校验
 * （prompt 与 override 二选一；override 意图不接受女仆维度），一次只表达一种写入。
 *
 * <ul>
 *   <li>maidUuid 空 + prompt 有值 → 写全局段 Prompt；</li>
 *   <li>maidUuid 有值 + prompt 有值 → 写该女仆单只段 Prompt（服务端校验归属）；</li>
 *   <li>override 有值（maidUuid 必须为空）→ 写「全局覆盖单只」开关。</li>
 * </ul>
 */
public class CustomPromptConfigSetMessage {

    private final Optional<UUID> maidUuid;
    private final Optional<String> prompt;
    private final Optional<Boolean> override;

    public CustomPromptConfigSetMessage(Optional<UUID> maidUuid, Optional<String> prompt, Optional<Boolean> override) {
        this.maidUuid = maidUuid;
        this.prompt = prompt;
        this.override = override;
    }

    public static void encode(CustomPromptConfigSetMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.maidUuid.isPresent());
        msg.maidUuid.ifPresent(buf::writeUUID);
        buf.writeBoolean(msg.prompt.isPresent());
        msg.prompt.ifPresent(p -> buf.writeUtf(p, CustomPromptConfigResponseMessage.MAX_WIRE_LENGTH));
        buf.writeBoolean(msg.override.isPresent());
        msg.override.ifPresent(buf::writeBoolean);
    }

    public static CustomPromptConfigSetMessage decode(FriendlyByteBuf buf) {
        Optional<UUID> maidUuid = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
        Optional<String> prompt = buf.readBoolean() ? Optional.of(buf.readUtf(CustomPromptConfigResponseMessage.MAX_WIRE_LENGTH)) : Optional.empty();
        Optional<Boolean> override = buf.readBoolean() ? Optional.of(buf.readBoolean()) : Optional.empty();
        return new CustomPromptConfigSetMessage(maidUuid, prompt, override);
    }

    public static void handle(CustomPromptConfigSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null || !SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                return;
            }
            // 管理员闸门（纵深防御）：关闭玩家自定义配置时拒绝写入，
            // 注入侧另有同闸门兜底（已落盘的 Prompt 不会生效）
            if (!Config.PLAYER_OPTION_ENABLED.get()) {
                return;
            }
            boolean hasPrompt = msg.prompt.isPresent();
            boolean hasOverride = msg.override.isPresent();
            // 意图互斥：prompt 与 override 恰有其一，否则拒绝
            if (hasPrompt == hasOverride) {
                return;
            }
            if (hasOverride) {
                // 覆盖开关是玩家级设置，不接受女仆维度（防歧义包）
                if (msg.maidUuid.isPresent()) {
                    return;
                }
                PlayerSettingsStore.setCustomPromptOverride(
                        serverPlayer.server, serverPlayer.getUUID(), msg.override.get());
                return;
            }
            String prompt = SelfTalkPackets.sanitizePromptText(msg.prompt.get());
            if (msg.maidUuid.isEmpty()) {
                PlayerSettingsStore.setCustomPromptGlobal(serverPlayer.server, serverPlayer.getUUID(), prompt);
            } else {
                UUID maidUuid = msg.maidUuid.get();
                // 归属校验：仅允许主人操作自己拥有的女仆，防伪造 UUID 污染他人数据
                if (!SelfTalkPackets.isOwnedMaid(serverPlayer, maidUuid)) {
                    return;
                }
                // Prompt 单只条目数上限（防恶意客户端伪造任意 UUID 无限撑大世界存档）；
                // 已存在条目的改写不计数（与两个布尔名单 handler 的容量检查口径一致）
                if (!prompt.isBlank()
                        && PlayerSettingsStore.getCustomPromptForMaid(serverPlayer.server, serverPlayer.getUUID(), maidUuid).isBlank()
                        && PlayerSettingsStore.countCustomPromptMaidEntries(
                        serverPlayer.server, serverPlayer.getUUID()) >= 256) {
                    MaidSelfTalkMod.LOGGER.warn("Player {} custom prompt maid list full, ignored",
                            serverPlayer.getUUID());
                    return;
                }
                PlayerSettingsStore.setCustomPromptForMaid(serverPlayer.server, serverPlayer.getUUID(), maidUuid, prompt);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
