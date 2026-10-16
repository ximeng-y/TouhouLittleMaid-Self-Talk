package com.maidmod.selftalk.network;

import com.maidmod.selftalk.persona.PersonaSplitService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S：请求把某只女仆的原版人设文本用 AI 拆分成四栏。
 * <p>
 * {@code sourceText} 是客户端在原版「✎ 人设编辑」页冻结的输入框文本（含未保存修改），
 * 直传原文：不做截断、控制字符过滤与段标签清洗（与人设保存链同一策略）。
 * 流侧上限与原版输入框字符上限一致；服务端另做业务校验并回固定结果码。
 * <p>
 * 同时携带女仆 UUID：与 entityId 双重校验，防实体 id 复用后把请求落到另一只女仆身上。
 */
public class MaidPersonaSplitRequestMessage {

    /** 原文流侧上限：与原版人设输入框的字符上限一致 */
    public static final int MAX_SOURCE_LENGTH = 4096;

    private final UUID requestId;
    private final int entityId;
    private final UUID maidUuid;
    private final String sourceText;

    public MaidPersonaSplitRequestMessage(UUID requestId, int entityId, UUID maidUuid, String sourceText) {
        this.requestId = requestId;
        this.entityId = entityId;
        this.maidUuid = maidUuid;
        this.sourceText = sourceText == null ? "" : sourceText;
    }

    public static void encode(MaidPersonaSplitRequestMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requestId);
        buf.writeVarInt(msg.entityId);
        buf.writeUUID(msg.maidUuid);
        buf.writeUtf(msg.sourceText, MAX_SOURCE_LENGTH);
    }

    public static MaidPersonaSplitRequestMessage decode(FriendlyByteBuf buf) {
        return new MaidPersonaSplitRequestMessage(buf.readUUID(), buf.readVarInt(), buf.readUUID(),
                buf.readUtf(MAX_SOURCE_LENGTH));
    }

    public static void handle(MaidPersonaSplitRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer serverPlayer = ctx.get().getSender();
            if (serverPlayer == null) {
                return;
            }
            PersonaSplitService.handleSplitRequest(serverPlayer, msg.requestId, msg.entityId,
                    msg.maidUuid, msg.sourceText);
        });
        ctx.get().setPacketHandled(true);
    }
}
