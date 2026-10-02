package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.EnvironmentContextMode;
import com.maidmod.selftalk.EnvironmentContextOption;
import com.maidmod.selftalk.EnvironmentContextSettings;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：提交环境上下文单项模式（玩家全局偏好，作用于该玩家名下全部女仆）。
 * <p>
 * 发送的是<b>目标绝对模式</b>而不是「切换一次」命令：丢包／乱序时不会把模式切到意料之外的状态，
 * 客户端也无需依赖本地推断链条。包内不携带 player UUID 或 maid UUID（语义上就没有这两个维度）。
 *
 * @param sessionId  浮层会话 id
 * @param seq        该会话内的请求序号
 * @param contextKey 目录条目 key，最长 {@link #MAX_KEY_LENGTH} 字符
 * @param modeId     模式存储值（{@code random}/{@code always}/{@code never}），最长 {@link #MAX_MODE_LENGTH} 字符
 */
public class EnvironmentContextConfigSetMessage {

    /** 目录 key 长度上限（编解码先校验，防超长字符串撑包） */
    public static final int MAX_KEY_LENGTH = 128;
    /** 模式 id 长度上限 */
    public static final int MAX_MODE_LENGTH = 16;

    private final UUID sessionId;
    private final long seq;
    private final String contextKey;
    private final String modeId;

    public EnvironmentContextConfigSetMessage(UUID sessionId, long seq, String contextKey, String modeId) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.contextKey = contextKey;
        this.modeId = modeId;
    }

    public static void encode(EnvironmentContextConfigSetMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sessionId);
        buf.writeVarLong(msg.seq);
        buf.writeUtf(msg.contextKey == null ? "" : msg.contextKey, MAX_KEY_LENGTH);
        buf.writeUtf(msg.modeId == null ? "" : msg.modeId, MAX_MODE_LENGTH);
    }

    public static EnvironmentContextConfigSetMessage decode(FriendlyByteBuf buf) {
        return new EnvironmentContextConfigSetMessage(buf.readUUID(), buf.readVarLong(),
                buf.readUtf(MAX_KEY_LENGTH), buf.readUtf(MAX_MODE_LENGTH));
    }

    /**
     * 纵深防御，不信任客户端：逐项复检管理权限、合法 key、合法 mode 与服务端功能可用性，
     * 任一条不过就只回快照、不改存档。
     * <p>
     * 这里刻意<b>不</b>把「管理员禁用／提供者未注册」当作非法 key：这些偏好必须保留，
     * 功能恢复后继续生效。同值 Set 也回快照（客户端据此清掉「同步中」），但存储层不产生无谓写入。
     */
    public static void handle(EnvironmentContextConfigSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null || msg.sessionId == null
                    || !SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                return;
            }
            EnvironmentContextOption option = EnvironmentContextOption.byKey(msg.contextKey);
            EnvironmentContextMode mode = EnvironmentContextMode.fromId(msg.modeId);
            if (option != null && mode != null
                    && Config.PLAYER_OPTION_ENABLED.get()
                    && EnvironmentContextSettings.availabilityOf(option).configurable()) {
                PlayerSettingsStore.setEnvironmentContextMode(
                        serverPlayer.server, serverPlayer.getUUID(), option, mode);
            }
            SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    EnvironmentContextConfigResponseMessage.snapshot(
                            serverPlayer.server, serverPlayer.getUUID(), msg.sessionId, msg.seq));
        });
        ctx.get().setPacketHandled(true);
    }
}
