package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.persona.PersonaSplitService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * S2C：AI 拆分人设的结果。
 * <p>
 * 每个被受理的拆分任务在终态（成功/失败/超时）都会收到一条回包；校验失败的请求
 * 也在处理当场回包。客户端只处理本连接登记过且 requestId + 女仆 UUID 匹配的响应。
 * <p>
 * 成功时四栏为拆分结果（单栏不超过 {@link #MAX_WIRE_FIELD_LENGTH}）；失败时四栏为
 * 空占位，客户端不得使用。候选只用于页面填入，保存仍走既有保存链并受每栏 4096 上限约束。
 *
 * @param requestId  对应请求的 id
 * @param maidUuid   女仆 UUID（客户端核对条目身份）
 * @param resultCode 固定结果码，见 {@link #RESULT_OK} 等常量
 */
public record MaidPersonaSplitResponsePayload(UUID requestId, UUID maidUuid, String resultCode,
                                              String identity, String personality,
                                              String background, String hobbies)
        implements CustomPacketPayload {

    public static final String RESULT_OK = "ok";
    public static final String RESULT_BUSY = "busy";
    public static final String RESULT_RATE_LIMITED = "rate_limited";
    public static final String RESULT_NOT_LOADED = "not_loaded";
    public static final String RESULT_NOT_OWNER = "not_owner";
    public static final String RESULT_EMPTY_SOURCE = "empty_source";
    public static final String RESULT_TOO_LONG = "too_long";
    public static final String RESULT_UNAVAILABLE = "unavailable";
    public static final String RESULT_FORMAT = "format";
    public static final String RESULT_NETWORK = "network";
    public static final String RESULT_TIMEOUT = "timeout";

    /** 单栏流侧上限：取四栏上限之和，仅作解码兜底（每栏 4096 校验在服务端，超限明确失败不截断） */
    public static final int MAX_WIRE_FIELD_LENGTH = PersonaSplitService.MAX_CANDIDATE_WIRE_LENGTH;

    /** 结果码流侧上限：固定短码，留出余量即可 */
    public static final int MAX_CODE_LENGTH = 32;

    public static final Type<MaidPersonaSplitResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "maid_persona_split_response"));

    /**
     * 手写编解码：失败回包同样占位写出（四栏空串），保持结构固定。
     */
    public static final StreamCodec<FriendlyByteBuf, MaidPersonaSplitResponsePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUUID(payload.requestId());
                        buf.writeUUID(payload.maidUuid());
                        buf.writeUtf(payload.resultCode(), MAX_CODE_LENGTH);
                        buf.writeUtf(payload.identity(), MAX_WIRE_FIELD_LENGTH);
                        buf.writeUtf(payload.personality(), MAX_WIRE_FIELD_LENGTH);
                        buf.writeUtf(payload.background(), MAX_WIRE_FIELD_LENGTH);
                        buf.writeUtf(payload.hobbies(), MAX_WIRE_FIELD_LENGTH);
                    },
                    buf -> new MaidPersonaSplitResponsePayload(buf.readUUID(), buf.readUUID(),
                            buf.readUtf(MAX_CODE_LENGTH),
                            buf.readUtf(MAX_WIRE_FIELD_LENGTH), buf.readUtf(MAX_WIRE_FIELD_LENGTH),
                            buf.readUtf(MAX_WIRE_FIELD_LENGTH), buf.readUtf(MAX_WIRE_FIELD_LENGTH)));

    /** 是否拆分成功（结果码为 {@link #RESULT_OK}） */
    public boolean success() {
        return RESULT_OK.equals(resultCode);
    }

    /** 拆分结果四栏快照（失败回包为全空占位，调用方须先判 {@link #success()}） */
    public MaidPersonaDraft toDraft() {
        return new MaidPersonaDraft(identity, personality, background, hobbies);
    }

    /** 成功回包：四栏即拆分结果 */
    public static MaidPersonaSplitResponsePayload ok(UUID requestId, UUID maidUuid, MaidPersonaDraft draft) {
        return new MaidPersonaSplitResponsePayload(requestId, maidUuid, RESULT_OK,
                draft.identity(), draft.personality(), draft.background(), draft.hobbies());
    }

    /** 失败回包：四栏为空占位，客户端不得使用 */
    public static MaidPersonaSplitResponsePayload failure(UUID requestId, UUID maidUuid, String resultCode) {
        return new MaidPersonaSplitResponsePayload(requestId, maidUuid, resultCode, "", "", "", "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
