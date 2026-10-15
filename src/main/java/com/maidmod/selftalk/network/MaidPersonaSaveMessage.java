package com.maidmod.selftalk.network;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：保存某只女仆的结构化人设草稿（整份替换）。
 * <p>
 * 四个栏位按平台原生有界编解码直传原文：不做截断、控制字符过滤与段标签清洗
 * （与自定义 Prompt 的写入清洗策略刻意不同——草稿不参与聊天，界面与存档保留玩家输入）。
 * 合计长度校验在服务端做（超限回 {@code too_long}，不写入），流侧上限按单栏放宽，
 * 让「单栏合法但合计超限」的请求能正常解码并被业务拒绝，而不是在解码期直接断开。
 * <p>
 * 与其它设置包不同，本包<b>一律回包</b>（{@link MaidPersonaSaveResponseMessage}，含失败结果），
 * 页面据此区分「已保存」与「被拒绝」，不会把「包已发出」当成保存成功。
 */
public class MaidPersonaSaveMessage {

    /** 单栏流侧上限：等于四栏合计上限（四栏各占满也仍可解码，由服务端按合计判定超限） */
    public static final int MAX_WIRE_FIELD_LENGTH = MaidPersonaDraft.MAX_TOTAL_LENGTH;

    private final UUID requestId;
    private final int entityId;
    private final String identity;
    private final String personality;
    private final String background;
    private final String hobbies;

    public MaidPersonaSaveMessage(UUID requestId, int entityId, String identity, String personality,
                                  String background, String hobbies) {
        this.requestId = requestId;
        this.entityId = entityId;
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

    /** 本次提交的四栏快照（不可变值对象，供服务端整份替换使用） */
    public MaidPersonaDraft toDraft() {
        return new MaidPersonaDraft(identity, personality, background, hobbies);
    }

    public static void encode(MaidPersonaSaveMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requestId);
        buf.writeVarInt(msg.entityId);
        buf.writeUtf(msg.identity, MAX_WIRE_FIELD_LENGTH);
        buf.writeUtf(msg.personality, MAX_WIRE_FIELD_LENGTH);
        buf.writeUtf(msg.background, MAX_WIRE_FIELD_LENGTH);
        buf.writeUtf(msg.hobbies, MAX_WIRE_FIELD_LENGTH);
    }

    public static MaidPersonaSaveMessage decode(FriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        int entityId = buf.readVarInt();
        String identity = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        String personality = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        String background = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        String hobbies = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        return new MaidPersonaSaveMessage(requestId, entityId, identity, personality, background, hobbies);
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
     * 写入路径：先归零再逐条校验，任一条不过都回失败结果（不静默丢弃），
     * 全通过后整份替换宿主草稿——不逐栏写入，也不触碰原版整段人设。
     * 返回 null 表示请求 id 缺失、回包无从匹配，此时只能丢弃。
     */
    private static MaidPersonaSaveResponseMessage save(ServerPlayer player, MaidPersonaSaveMessage msg) {
        UUID requestId = msg.requestId;
        if (requestId == null) {
            return null;
        }
        if (!SelfTalkPackets.allowConfigPacket(player.getUUID())) {
            return new MaidPersonaSaveResponseMessage(requestId, false,
                    MaidPersonaSaveResponseMessage.RESULT_RATE_LIMITED);
        }
        if (!(player.serverLevel().getEntity(msg.entityId) instanceof EntityMaid maid)) {
            return new MaidPersonaSaveResponseMessage(requestId, false,
                    MaidPersonaSaveResponseMessage.RESULT_NOT_LOADED);
        }
        if (!player.getUUID().equals(maid.getOwnerUUID())) {
            return new MaidPersonaSaveResponseMessage(requestId, false,
                    MaidPersonaSaveResponseMessage.RESULT_NOT_OWNER);
        }
        MaidPersonaDraft draft = msg.toDraft();
        if (!draft.withinLimit()) {
            return new MaidPersonaSaveResponseMessage(requestId, false,
                    MaidPersonaSaveResponseMessage.RESULT_TOO_LONG);
        }
        if (!MaidPersonaHost.replace(maid, draft)) {
            return new MaidPersonaSaveResponseMessage(requestId, false,
                    MaidPersonaSaveResponseMessage.RESULT_UNAVAILABLE);
        }
        return new MaidPersonaSaveResponseMessage(requestId, true, MaidPersonaSaveResponseMessage.RESULT_OK);
    }
}
