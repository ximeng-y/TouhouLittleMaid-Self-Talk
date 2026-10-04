package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：提交历史上下文模式（玩家级设置，作用于该玩家名下全部女仆）。
 * <p>
 * 发送的是<b>目标绝对模式</b>而不是「切换一次」命令：丢包／乱序时不会把模式切到意料之外的状态，
 * 客户端也无需依赖本地推断链条。包内<b>不携带</b>玩家或女仆 UUID——语义上就没有这两个维度，
 * 服务端一律用连接上的 {@code ServerPlayer} 身份读写。
 * <p>
 * 与环境上下文共用同一个浮层会话：{@code sessionId} 与递增 {@code seq} 都取自当前浮层会话，
 * 因此旧会话或过期响应不会覆盖新状态。
 *
 * @param sessionId 浮层会话 id
 * @param seq       该会话内的请求序号
 * @param modeId    目标绝对模式的存储值（{@code full} / {@code compact} / {@code retrieval}）
 */
public record HistoryContextModeSetPayload(UUID sessionId, long seq, String modeId)
        implements CustomPacketPayload {

    public static final Type<HistoryContextModeSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID,
                    "history_context_mode_set"));

    /** 模式存储值最大长度（对齐环境上下文 set 的同名上限口径） */
    static final int MAX_MODE_LENGTH = 32;

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    private static final StreamCodec<FriendlyByteBuf, String> MODE_CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUtf(value == null ? "" : value, MAX_MODE_LENGTH),
            buf -> buf.readUtf(MAX_MODE_LENGTH));

    public static final StreamCodec<FriendlyByteBuf, HistoryContextModeSetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUID_STREAM_CODEC, HistoryContextModeSetPayload::sessionId,
                    ByteBufCodecs.VAR_LONG, HistoryContextModeSetPayload::seq,
                    MODE_CODEC, HistoryContextModeSetPayload::modeId,
                    HistoryContextModeSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
