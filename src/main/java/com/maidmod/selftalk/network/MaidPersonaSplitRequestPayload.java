package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：请求把某只女仆的原版人设文本用 AI 拆分成四栏。
 * <p>
 * {@code sourceText} 是客户端在原版「✎ 人设编辑」页冻结的输入框文本（含未保存修改），
 * 直传原文：不做截断、控制字符过滤与段标签清洗（与人设保存链同一策略）。
 * 流侧上限与原版输入框字符上限一致；服务端另做业务校验并回固定结果码。
 * <p>
 * 同时携带女仆 UUID：与 entityId 双重校验，防实体 id 复用后把请求落到另一只女仆身上。
 *
 * @param requestId  本次请求的 id（回包按它匹配，避免迟到响应被当作本次结果）
 * @param entityId   女仆实体 id（服务端在玩家当前维度查找该实体）
 * @param maidUuid   女仆 UUID（与 entityId 双重校验）
 * @param sourceText 原版输入框冻结文本
 */
public record MaidPersonaSplitRequestPayload(UUID requestId, int entityId, UUID maidUuid,
                                             String sourceText)
        implements CustomPacketPayload {

    /** 原文流侧上限：与原版人设输入框的字符上限一致 */
    public static final int MAX_SOURCE_LENGTH = 4096;

    public static final Type<MaidPersonaSplitRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "maid_persona_split_request"));

    public static final StreamCodec<FriendlyByteBuf, MaidPersonaSplitRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUUID(payload.requestId());
                        buf.writeVarInt(payload.entityId());
                        buf.writeUUID(payload.maidUuid());
                        buf.writeUtf(payload.sourceText(), MAX_SOURCE_LENGTH);
                    },
                    buf -> new MaidPersonaSplitRequestPayload(buf.readUUID(), buf.readVarInt(),
                            buf.readUUID(), buf.readUtf(MAX_SOURCE_LENGTH)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
