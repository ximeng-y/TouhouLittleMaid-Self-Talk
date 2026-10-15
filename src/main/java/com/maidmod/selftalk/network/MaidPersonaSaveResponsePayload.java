package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * S2C：结构化人设草稿保存的结果确认。
 * <p>
 * 每个可正常解码的保存请求都会收到一条回包（含失败），客户端据此区分「已保存」与「被拒绝」，
 * 不会把「包已发出」当成保存成功。
 *
 * @param requestId  对应请求的 id（客户端只处理与当前页面匹配的那一条）
 * @param success    是否成功写入
 * @param resultCode 固定结果码，见 {@link #RESULT_OK} 等常量
 */
public record MaidPersonaSaveResponsePayload(UUID requestId, boolean success, String resultCode)
        implements CustomPacketPayload {

    public static final String RESULT_OK = "ok";
    public static final String RESULT_RATE_LIMITED = "rate_limited";
    public static final String RESULT_NOT_LOADED = "not_loaded";
    public static final String RESULT_NOT_OWNER = "not_owner";
    public static final String RESULT_TOO_LONG = "too_long";
    public static final String RESULT_UNAVAILABLE = "unavailable";

    /** 结果码流侧上限：固定短码，留出余量即可 */
    public static final int MAX_CODE_LENGTH = 32;

    public static final Type<MaidPersonaSaveResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "maid_persona_save_response"));

    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    public static final StreamCodec<FriendlyByteBuf, MaidPersonaSaveResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUID_STREAM_CODEC, MaidPersonaSaveResponsePayload::requestId,
                    ByteBufCodecs.BOOL, MaidPersonaSaveResponsePayload::success,
                    ByteBufCodecs.stringUtf8(MAX_CODE_LENGTH), MaidPersonaSaveResponsePayload::resultCode,
                    MaidPersonaSaveResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
