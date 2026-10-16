package com.maidmod.selftalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.MaidPersonaMode;
import com.maidmod.selftalk.MaidPersonaRuntime;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：保存某只女仆的人设配置（模式 + 四栏内容，整份替换）。
 * <p>
 * 四个栏位按平台原生有界编解码直传原文：不做截断、控制字符过滤与段标签清洗
 * （与自定义 Prompt 的写入清洗策略刻意不同——人设内容原样保留，界面与存档保留玩家输入）。
 * 合计长度校验在服务端做（超限回 {@code too_long}，不写入），流侧上限按单栏放宽，
 * 让「单栏合法但合计超限」的请求能正常解码并被业务拒绝，而不是在解码期直接断开。
 * <p>
 * 同时携带女仆 UUID：实体 id 只在单个连接内、且只在发送时刻的维度内有效，
 * 服务端必须同时核对 id 与 UUID 才能确认「收到请求的这只，就是玩家当初编辑的那只」。
 * <p>
 * 与其它设置包不同，本包<b>一律回包</b>（{@link MaidPersonaSaveResponseMessage}，含失败结果），
 * 页面据此区分「已保存」与「被拒绝」，不会把「包已发出」当成保存成功。
 */
public class MaidPersonaSaveMessage {

    /** 单栏流侧上限：等于四栏合计上限（四栏各占满也仍可解码，由服务端按合计判定超限） */
    public static final int MAX_WIRE_FIELD_LENGTH = MaidPersonaDraft.MAX_TOTAL_LENGTH;

    /** 模式字段流侧上限：固定短标签，留出余量 */
    public static final int MAX_MODE_LENGTH = 32;

    private final UUID requestId;
    private final int entityId;
    private final UUID maidUuid;
    private final String mode;
    private final String identity;
    private final String personality;
    private final String background;
    private final String hobbies;

    public MaidPersonaSaveMessage(UUID requestId, int entityId, UUID maidUuid, String mode, String identity,
                                  String personality, String background, String hobbies) {
        this.requestId = requestId;
        this.entityId = entityId;
        this.maidUuid = maidUuid;
        this.mode = mode == null ? "" : mode;
        this.identity = identity == null ? "" : identity;
        this.personality = personality == null ? "" : personality;
        this.background = background == null ? "" : background;
        this.hobbies = hobbies == null ? "" : hobbies;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public int getEntityId() {
        return entityId;
    }

    public UUID getMaidUuid() {
        return maidUuid;
    }

    /** 本次提交的四栏快照（不可变值对象） */
    public MaidPersonaDraft toDraft() {
        return new MaidPersonaDraft(identity, personality, background, hobbies);
    }

    /** 本次提交的完整配置快照：模式与四栏一次成形，服务端整份替换用 */
    public MaidPersonaConfig toConfig() {
        return new MaidPersonaConfig(MaidPersonaMode.fromTag(mode), toDraft());
    }

    public static void encode(MaidPersonaSaveMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requestId);
        buf.writeVarInt(msg.entityId);
        buf.writeUUID(msg.maidUuid);
        buf.writeUtf(msg.mode, MAX_MODE_LENGTH);
        buf.writeUtf(msg.identity, MAX_WIRE_FIELD_LENGTH);
        buf.writeUtf(msg.personality, MAX_WIRE_FIELD_LENGTH);
        buf.writeUtf(msg.background, MAX_WIRE_FIELD_LENGTH);
        buf.writeUtf(msg.hobbies, MAX_WIRE_FIELD_LENGTH);
    }

    public static MaidPersonaSaveMessage decode(FriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        int entityId = buf.readVarInt();
        UUID maidUuid = buf.readUUID();
        String mode = buf.readUtf(MAX_MODE_LENGTH);
        String identity = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        String personality = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        String background = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        String hobbies = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        return new MaidPersonaSaveMessage(requestId, entityId, maidUuid, mode, identity, personality, background,
                hobbies);
    }

    public static void handle(MaidPersonaSaveMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null) {
                return;
            }
            MaidPersonaSaveResponseMessage response = save(serverPlayer, msg);
            if (response != null) {
                SelfTalkPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), response);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 写入路径：按顺序校验后整份替换宿主配置，返回固定结果码对应回包（null = 无法匹配的请求，丢弃）。
     * 模式与四栏一次写入，不逐栏写入，也不触碰原版整段人设。
     */
    private static MaidPersonaSaveResponseMessage save(ServerPlayer player, MaidPersonaSaveMessage msg) {
        UUID requestId = msg.requestId;
        UUID maidUuid = msg.maidUuid;
        int entityId = msg.entityId;
        if (requestId == null) {
            // 请求 id 缺失则回包无从匹配，直接丢弃（页面在等待超时后自行解除，不会误判为成功）
            return null;
        }
        if (!SelfTalkPackets.allowConfigPacket(player.getUUID())) {
            return MaidPersonaSaveResponseMessage.failure(requestId, entityId, maidUuid,
                    MaidPersonaSaveResponseMessage.RESULT_RATE_LIMITED);
        }
        if (!(player.serverLevel().getEntity(entityId) instanceof EntityMaid maid)) {
            return MaidPersonaSaveResponseMessage.failure(requestId, entityId, maidUuid,
                    MaidPersonaSaveResponseMessage.RESULT_NOT_LOADED);
        }
        // 双重身份校验：实体 id 只在单连接、单维度内有效，必须同时核对 UUID，
        // 否则实体 id 复用（旧女仆卸载、新女仆顶上同一 id）会让请求改到另一只女仆身上
        if (maidUuid == null || !maid.getUUID().equals(maidUuid)) {
            return MaidPersonaSaveResponseMessage.failure(requestId, entityId, maidUuid,
                    MaidPersonaSaveResponseMessage.RESULT_NOT_LOADED);
        }
        if (!player.getUUID().equals(maid.getOwnerUUID())) {
            return MaidPersonaSaveResponseMessage.failure(requestId, entityId, maidUuid,
                    MaidPersonaSaveResponseMessage.RESULT_NOT_OWNER);
        }
        MaidPersonaConfig config = msg.toConfig();
        if (!config.draft().withinLimit()) {
            // 模式与内容一次成形：长度只按四栏合计判定（模式标签是固定短值）
            return MaidPersonaSaveResponseMessage.failure(requestId, entityId, maidUuid,
                    MaidPersonaSaveResponseMessage.RESULT_TOO_LONG);
        }
        if (!MaidPersonaHost.replace(maid, config)) {
            return MaidPersonaSaveResponseMessage.failure(requestId, entityId, maidUuid,
                    MaidPersonaSaveResponseMessage.RESULT_UNAVAILABLE);
        }
        // 配置已变：清理人设索引与在链召回结果，并作废旧快照的在途规划
        MaidPersonaRuntime.onConfigChanged(maid);
        return MaidPersonaSaveResponseMessage.ok(requestId, entityId, maidUuid, config);
    }
}
