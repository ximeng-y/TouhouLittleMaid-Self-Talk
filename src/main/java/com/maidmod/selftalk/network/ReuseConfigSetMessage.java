package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：提交预制复用设置的<b>目标绝对操作码</b>（不是「切换一次」命令：丢包／乱序时
 * 不会把设置切到意料之外的状态，客户端也无需依赖本地推断链条）。
 * <p>
 * 包内不携带 player UUID：服务端一律用连接上的 {@link ServerPlayer} 身份读写。
 * <p>
 * 操作码（0~4）：
 * <ul>
 *   <li>0 全局关、1 全局开：女仆维度必须为空（防歧义包，携带即拒写）；</li>
 *   <li>2 恢复继承（删除覆盖条目）、3 单只关、4 单只开：女仆 UUID 必须非空，
 *       且须通过归属校验（仅允许操作自己拥有的女仆）。</li>
 * </ul>
 * 两种显式布尔都占单只覆盖条目容量；删除覆盖（恢复继承）不受容量限制。
 */
public class ReuseConfigSetMessage {

    /** 全局关 */
    public static final int OP_GLOBAL_OFF = 0;
    /** 全局开 */
    public static final int OP_GLOBAL_ON = 1;
    /** 单只恢复继承（删除覆盖条目） */
    public static final int OP_MAID_INHERIT = 2;
    /** 单只显式关 */
    public static final int OP_MAID_OFF = 3;
    /** 单只显式开 */
    public static final int OP_MAID_ON = 4;

    private final UUID sessionId;
    private final long seq;
    private final UUID maidUuid;
    private final int opcode;

    public ReuseConfigSetMessage(UUID sessionId, long seq, UUID maidUuid, int opcode) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.maidUuid = maidUuid;
        this.opcode = opcode;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public long seq() {
        return seq;
    }

    public UUID maidUuid() {
        return maidUuid;
    }

    public int opcode() {
        return opcode;
    }

    public static void encode(ReuseConfigSetMessage msg, FriendlyByteBuf buf) {
        ReuseConfigRequestMessage.writeNullableUuid(buf, msg.sessionId);
        buf.writeVarLong(msg.seq);
        ReuseConfigRequestMessage.writeNullableUuid(buf, msg.maidUuid);
        buf.writeVarInt(msg.opcode);
    }

    public static ReuseConfigSetMessage decode(FriendlyByteBuf buf) {
        return new ReuseConfigSetMessage(ReuseConfigRequestMessage.readNullableUuid(buf), buf.readVarLong(),
                ReuseConfigRequestMessage.readNullableUuid(buf), buf.readVarInt());
    }

    /**
     * 服务端：保存预制复用设置（绝对操作码，纵深防御，不信任客户端）。
     * <p>
     * 管理员禁用时<b>不改写任何已存偏好</b>（防「预埋等开闸」），仍回权威快照让界面
     * 显示原偏好与关闸状态。全局操作（0/1）要求女仆维度为空，单只操作（2/3/4）要求
     * 女仆 UUID 非空且通过归属校验；形状不符的歧义包直接丢弃不回包。
     * <p>
     * 归属校验失败、容量已满、非法操作码这三种「形状合法但未写入」的情况<b>照常回权威快照</b>：
     * 它们都<b>不触碰任何已存偏好</b>，而界面停在「同步中」比收到一份没改过的快照更糟——
     * 客户端也无从区分丢包与拒绝。新增覆盖条目前校验容量上限；删除条目（恢复继承）不计数。
     */
    public static void handle(ReuseConfigSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null || msg.sessionId == null
                    || !SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                return;
            }
            int opcode = msg.opcode;
            UUID maidUuid = msg.maidUuid;
            if (opcode == OP_GLOBAL_OFF || opcode == OP_GLOBAL_ON) {
                // 全局操作不接受女仆维度（防歧义包）
                if (maidUuid != null) {
                    return;
                }
                if (Config.PLAYER_OPTION_ENABLED.get()) {
                    PlayerSettingsStore.setPrebuiltReuseEnabled(
                            serverPlayer.server, serverPlayer.getUUID(), opcode == OP_GLOBAL_ON);
                }
            } else if (opcode == OP_MAID_INHERIT || opcode == OP_MAID_OFF || opcode == OP_MAID_ON) {
                if (maidUuid == null) {
                    return;
                }
                if (SelfTalkPackets.isOwnedMaid(serverPlayer, maidUuid)
                        && canWriteOverride(serverPlayer, maidUuid, opcode)
                        && Config.PLAYER_OPTION_ENABLED.get()) {
                    Boolean override;
                    if (opcode == OP_MAID_ON) {
                        override = Boolean.TRUE;
                    } else if (opcode == OP_MAID_OFF) {
                        override = Boolean.FALSE;
                    } else {
                        override = null;
                    }
                    PlayerSettingsStore.setPrebuiltReuseMaidOverride(
                            serverPlayer.server, serverPlayer.getUUID(), maidUuid, override);
                }
            }
            // 非法操作码不保存，只回权威快照防客户端同步悬挂
            SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    ReuseConfigResponseMessage.snapshot(serverPlayer.server, serverPlayer.getUUID(),
                            msg.sessionId, msg.seq, msg.maidUuid));
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 单只覆盖写入前的容量校验：仅在<b>新增条目</b>（恢复继承不新增；已有条目改值也不新增）时
     * 检查上线，防恶意客户端伪造任意 UUID 无限撑大世界存档。
     */
    private static boolean canWriteOverride(ServerPlayer serverPlayer, UUID maidUuid, int opcode) {
        if (opcode == OP_MAID_INHERIT) {
            return true;
        }
        if (PlayerSettingsStore.getPrebuiltReuseMaidOverride(
                serverPlayer.server, serverPlayer.getUUID(), maidUuid) != null) {
            return true;
        }
        if (PlayerSettingsStore.countPrebuiltReuseMaidOverrides(
                serverPlayer.server, serverPlayer.getUUID()) >= PlayerSettingsStore.MAX_MAID_OVERRIDES) {
            MaidSelfTalkMod.LOGGER.warn("Player {} prebuilt reuse maid list full ({}), ignored",
                    serverPlayer.getUUID(), PlayerSettingsStore.MAX_MAID_OVERRIDES);
            return false;
        }
        return true;
    }
}
