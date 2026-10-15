package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：保存某只女仆的结构化人设草稿（整份替换）。
 * <p>
 * 四个栏位按平台原生有界编解码直传原文：不做截断、控制字符过滤与段标签清洗
 * （与自定义 Prompt 的写入清洗策略刻意不同——草稿不参与聊天，界面与存档保留玩家输入）。
 * 合计长度校验在服务端做（超限回 {@code too_long}，不写入），流侧上限按单栏放宽，
 * 让「单栏合法但合计超限」的请求能正常解码并被业务拒绝，而不是在解码期直接断开。
 *
 * @param requestId 本次提交的请求 id（回包按它匹配，避免迟到响应被当作本次结果）
 * @param entityId  女仆实体 id（服务端在玩家当前维度查找该实体）
 */
public record MaidPersonaSavePayload(UUID requestId, int entityId, String identity, String personality,
                                     String background, String hobbies) implements CustomPacketPayload {

    /** 单栏流侧上限：等于四栏合计上限（四栏各占满也仍可解码，由服务端按合计判定超限） */
    public static final int MAX_WIRE_FIELD_LENGTH = MaidPersonaDraft.MAX_TOTAL_LENGTH;

    public static final Type<MaidPersonaSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "maid_persona_save"));

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    private static final StreamCodec<ByteBuf, String> FIELD_STREAM_CODEC =
            ByteBufCodecs.stringUtf8(MAX_WIRE_FIELD_LENGTH);

    public static final StreamCodec<FriendlyByteBuf, MaidPersonaSavePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUID_STREAM_CODEC, MaidPersonaSavePayload::requestId,
                    ByteBufCodecs.VAR_INT, MaidPersonaSavePayload::entityId,
                    FIELD_STREAM_CODEC, MaidPersonaSavePayload::identity,
                    FIELD_STREAM_CODEC, MaidPersonaSavePayload::personality,
                    FIELD_STREAM_CODEC, MaidPersonaSavePayload::background,
                    FIELD_STREAM_CODEC, MaidPersonaSavePayload::hobbies,
                    MaidPersonaSavePayload::new);

    /** 本次提交的四栏快照（不可变值对象，供服务端整份替换使用） */
    public MaidPersonaDraft toDraft() {
        return new MaidPersonaDraft(identity, personality, background, hobbies);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
