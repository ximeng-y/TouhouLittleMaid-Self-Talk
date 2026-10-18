package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.PlayerSettingsStore;
import com.maidmod.selftalk.PrebuiltReuseParameters;
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
 * 操作码（0~4 为原有复用开关，5~10 为 issue #108 新增匹配参数）：
 * <ul>
 *   <li>0 全局关、1 全局开：女仆维度必须为空（防歧义包，携带即拒写）；</li>
 *   <li>2 恢复继承（删除覆盖条目）、3 单只关、4 单只开：女仆 UUID 必须非空，
 *       且须通过归属校验（仅允许操作自己拥有的女仆）；</li>
 *   <li>5 设置跟随服务器（{@code value} 仅 0／1）、6 个人最低条数、7 个人概率：
 *       女仆维度必须为空；个人数值还要求玩家当前已取消跟随；</li>
 *   <li>8 服务器默认最低条数、9 服务器默认概率、10 是否允许个人参数（{@code value} 仅 0／1）：
 *       女仆维度必须为空，且每次重新校验管理员身份。</li>
 * </ul>
 * 新增操作按<b>单字段</b>发送绝对目标值，避免失焦提交时顺带覆盖其他字段，
 * 也避免两个管理员编辑不同字段时互相覆盖整份旧快照。
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
    /** 设置跟随服务器（value 0 = 取消跟随，1 = 跟随） */
    public static final int OP_FOLLOW_SERVER = 5;
    /** 设置个人最低可用条数 */
    public static final int OP_PLAYER_MIN_ELIGIBLE = 6;
    /** 设置个人使用概率 */
    public static final int OP_PLAYER_HIT_CHANCE = 7;
    /** 设置服务器默认最低可用条数（仅管理员） */
    public static final int OP_SERVER_MIN_ELIGIBLE = 8;
    /** 设置服务器默认使用概率（仅管理员） */
    public static final int OP_SERVER_HIT_CHANCE = 9;
    /** 设置是否允许玩家自定义参数（仅管理员；value 0／1） */
    public static final int OP_PLAYER_PARAMETERS_ALLOWED = 10;

    private final UUID sessionId;
    private final long seq;
    private final UUID maidUuid;
    private final int opcode;
    private final int value;

    public ReuseConfigSetMessage(UUID sessionId, long seq, UUID maidUuid, int opcode) {
        this(sessionId, seq, maidUuid, opcode, 0);
    }

    public ReuseConfigSetMessage(UUID sessionId, long seq, UUID maidUuid, int opcode, int value) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.maidUuid = maidUuid;
        this.opcode = opcode;
        this.value = value;
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

    public int value() {
        return value;
    }

    public static void encode(ReuseConfigSetMessage msg, FriendlyByteBuf buf) {
        ReuseConfigRequestMessage.writeNullableUuid(buf, msg.sessionId);
        buf.writeVarLong(msg.seq);
        ReuseConfigRequestMessage.writeNullableUuid(buf, msg.maidUuid);
        buf.writeVarInt(msg.opcode);
        buf.writeVarInt(msg.value);
    }

    public static ReuseConfigSetMessage decode(FriendlyByteBuf buf) {
        return new ReuseConfigSetMessage(ReuseConfigRequestMessage.readNullableUuid(buf), buf.readVarLong(),
                ReuseConfigRequestMessage.readNullableUuid(buf), buf.readVarInt(), buf.readVarInt());
    }

    /**
     * 服务端：保存预制复用设置（绝对操作码，纵深防御，不信任客户端）。
     * <p>
     * 管理员禁用时<b>不改写任何已存偏好</b>（防「预埋等开闸」），仍回权威快照让界面
     * 显示原偏好与关闸状态。全局操作（0/1）要求女仆维度为空，单只操作（2/3/4）要求
     * 女仆 UUID 非空且通过归属校验；形状不符的歧义包直接丢弃不回包。
     * <p>
     * 归属校验失败、容量已满、非法操作码、越权与越界数值这些「形状合法但未写入」的情况
     * <b>照常回权威快照</b>：它们都<b>不触碰任何已存偏好</b>，而界面停在「同步中」比收到
     * 一份没改过的快照更糟——客户端也无从区分丢包与拒绝。
     * 新增覆盖条目前校验容量上限；删除条目（恢复继承）不计数。
     * <p>
     * 新增数值操作先做完整形状与范围校验，再动任何字段：非法请求不会产生部分写入。
     */
    public static void handle(ReuseConfigSetMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null || msg.sessionId == null
                    || !SelfTalkPackets.allowConfigPacket(serverPlayer.getUUID())) {
                return;
            }
            apply(serverPlayer, msg);
            // 非法操作码 / 被拒绝的请求同样回权威快照，防客户端同步悬挂
            SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    ReuseConfigResponseMessage.snapshot(serverPlayer,
                            msg.sessionId, msg.seq, msg.maidUuid));
        });
        ctx.get().setPacketHandled(true);
    }

    /** 按操作码落库；所有拒绝路径都只返回、不写字段 */
    private static void apply(ServerPlayer serverPlayer, ReuseConfigSetMessage msg) {
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
            return;
        }
        if (opcode == OP_MAID_INHERIT || opcode == OP_MAID_OFF || opcode == OP_MAID_ON) {
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
            return;
        }
        if (opcode == OP_FOLLOW_SERVER) {
            // 个人参数操作都不带女仆维度
            if (maidUuid != null || (msg.value != 0 && msg.value != 1)) {
                return;
            }
            // 原总闸关闭时玩家设置一律不可写（与 0~4 同口径）
            if (!Config.PLAYER_OPTION_ENABLED.get()
                    || !PlayerSettingsStore.arePrebuiltReusePlayerParametersAllowed(serverPlayer.server)) {
                return;
            }
            PlayerSettingsStore.setPrebuiltReuseFollowingServer(
                    serverPlayer.server, serverPlayer.getUUID(), msg.value == 1);
            return;
        }
        if (opcode == OP_PLAYER_MIN_ELIGIBLE || opcode == OP_PLAYER_HIT_CHANCE) {
            if (maidUuid != null) {
                return;
            }
            boolean validValue = opcode == OP_PLAYER_MIN_ELIGIBLE
                    ? PrebuiltReuseParameters.isValidMinEligibleEntries(msg.value)
                    : PrebuiltReuseParameters.isValidHitChancePercent(msg.value);
            if (!validValue) {
                return;
            }
            if (!Config.PLAYER_OPTION_ENABLED.get()
                    || !PlayerSettingsStore.arePrebuiltReusePlayerParametersAllowed(serverPlayer.server)
                    || PlayerSettingsStore.isPrebuiltReuseFollowingServer(serverPlayer.server, serverPlayer.getUUID())) {
                // 数值写入本身不得隐式取消跟随
                return;
            }
            if (opcode == OP_PLAYER_MIN_ELIGIBLE) {
                PlayerSettingsStore.setPrebuiltReusePlayerMinEligible(serverPlayer.server, serverPlayer.getUUID(), msg.value);
            } else {
                PlayerSettingsStore.setPrebuiltReusePlayerHitChance(serverPlayer.server, serverPlayer.getUUID(), msg.value);
            }
            return;
        }
        if (opcode == OP_SERVER_MIN_ELIGIBLE || opcode == OP_SERVER_HIT_CHANCE
                || opcode == OP_PLAYER_PARAMETERS_ALLOWED) {
            if (maidUuid != null) {
                return;
            }
            // 管理员身份每次重新判定：不信任客户端快照或界面状态
            if (!PlayerSettingsStore.isPrebuiltReuseAdmin(serverPlayer)) {
                return;
            }
            if (opcode == OP_SERVER_MIN_ELIGIBLE) {
                if (PrebuiltReuseParameters.isValidMinEligibleEntries(msg.value)) {
                    PlayerSettingsStore.setPrebuiltReuseServerMinEligible(serverPlayer.server, msg.value);
                }
            } else if (opcode == OP_SERVER_HIT_CHANCE) {
                if (PrebuiltReuseParameters.isValidHitChancePercent(msg.value)) {
                    PlayerSettingsStore.setPrebuiltReuseServerHitChance(serverPlayer.server, msg.value);
                }
            } else if (msg.value == 0 || msg.value == 1) {
                PlayerSettingsStore.setPrebuiltReusePlayerParametersAllowed(
                        serverPlayer.server, msg.value == 1);
            }
        }
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
