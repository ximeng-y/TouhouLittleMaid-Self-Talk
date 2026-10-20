package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.persona.PersonaSplitService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C：AI 拆分人设的结果。
 * <p>
 * 每个被受理的拆分任务在终态（成功/失败/超时）都会收到一条回包；校验失败的请求
 * 也在处理当场回包。客户端只处理本连接登记过且 requestId + 女仆 UUID 匹配的响应。
 * <p>
 * 成功时四栏为拆分结果（单栏不超过 {@link #MAX_WIRE_FIELD_LENGTH}）；失败时四栏为
 * 空占位，客户端不得使用。候选只用于页面填入，保存仍走既有保存链并受每栏 4096 上限约束。
 */
public class MaidPersonaSplitResponseMessage {

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

    private final UUID requestId;
    private final UUID maidUuid;
    private final String resultCode;
    private final String identity;
    private final String personality;
    private final String background;
    private final String hobbies;

    public MaidPersonaSplitResponseMessage(UUID requestId, UUID maidUuid, String resultCode,
                                           String identity, String personality,
                                           String background, String hobbies) {
        this.requestId = requestId;
        this.maidUuid = maidUuid;
        this.resultCode = resultCode == null ? "" : resultCode;
        this.identity = identity == null ? "" : identity;
        this.personality = personality == null ? "" : personality;
        this.background = background == null ? "" : background;
        this.hobbies = hobbies == null ? "" : hobbies;
    }

    /** 成功回包：四栏即拆分结果 */
    public static MaidPersonaSplitResponseMessage ok(UUID requestId, UUID maidUuid, MaidPersonaDraft draft) {
        return new MaidPersonaSplitResponseMessage(requestId, maidUuid, RESULT_OK,
                draft.identity(), draft.personality(), draft.background(), draft.hobbies());
    }

    /** 失败回包：四栏为空占位，客户端不得使用 */
    public static MaidPersonaSplitResponseMessage failure(UUID requestId, UUID maidUuid, String resultCode) {
        return new MaidPersonaSplitResponseMessage(requestId, maidUuid, resultCode, "", "", "", "");
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getMaidUuid() {
        return maidUuid;
    }

    public String getResultCode() {
        return resultCode;
    }

    /** 是否拆分成功（结果码为 {@link #RESULT_OK}） */
    public boolean isSuccess() {
        return RESULT_OK.equals(resultCode);
    }

    /** 拆分结果四栏快照（失败回包为全空占位，调用方须先判 {@link #isSuccess()}） */
    public MaidPersonaDraft toDraft() {
        return new MaidPersonaDraft(identity, personality, background, hobbies);
    }

    /** 失败回包同样占位写出（四栏空串），保持结构固定 */
    public static void encode(MaidPersonaSplitResponseMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requestId);
        buf.writeUUID(msg.maidUuid);
        buf.writeUtf(msg.resultCode, MAX_CODE_LENGTH);
        buf.writeUtf(msg.identity, MAX_WIRE_FIELD_LENGTH);
        buf.writeUtf(msg.personality, MAX_WIRE_FIELD_LENGTH);
        buf.writeUtf(msg.background, MAX_WIRE_FIELD_LENGTH);
        buf.writeUtf(msg.hobbies, MAX_WIRE_FIELD_LENGTH);
    }

    public static MaidPersonaSplitResponseMessage decode(FriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        UUID maidUuid = buf.readUUID();
        String resultCode = buf.readUtf(MAX_CODE_LENGTH);
        String identity = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        String personality = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        String background = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        String hobbies = buf.readUtf(MAX_WIRE_FIELD_LENGTH);
        return new MaidPersonaSplitResponseMessage(requestId, maidUuid, resultCode,
                identity, personality, background, hobbies);
    }

    public static void handle(MaidPersonaSplitResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.maidmod.selftalk.client.MaidPersonaSplitClient.onSplitResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
