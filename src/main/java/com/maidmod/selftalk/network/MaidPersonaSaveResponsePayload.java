package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaMode;
import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * S2C：女仆人设配置保存的结果确认。
 * <p>
 * 每个可正常解码的保存请求都会收到一条回包（含失败），客户端据此区分「已保存」与「被拒绝」，
 * 不会把「包已发出」当成保存成功。
 * <p>
 * 回包带上完整身份（entityId + maidUuid）与服务端确认后的完整配置：
 * 客户端只处理自己登记过的 requestId，并核对女仆 UUID，
 * 然后<b>以服务端确认值为准</b>更新客户端宿主与基线——不信任本地提交值，
 * 避免「服务端按校验规则收敛过、客户端却按原值显示」的不一致。
 * 失败时 {@code confirmed} 无意义，客户端不得据此改写任何本地状态。
 *
 * @param requestId  对应请求的 id（客户端只处理与当前页面匹配的那一条）
 * @param entityId   女仆实体 id（与 maidUuid 一并用于客户端确认目标）
 * @param maidUuid   女仆 UUID（客户端核对当前实体身份，防实体 id 复用后改错女仆）
 * @param success    是否成功写入
 * @param resultCode 固定结果码，见 {@link #RESULT_OK} 等常量
 * @param confirmed  服务端确认后的完整配置（仅成功时有效；失败时回默认配置占位）
 */
public record MaidPersonaSaveResponsePayload(UUID requestId, int entityId, UUID maidUuid, boolean success,
                                             String resultCode, MaidPersonaConfig confirmed)
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

    /**
     * 手写编解码：字段数已超出 {@code StreamCodec.composite} 的分量上限（6 个）。
     * 确认配置按 (mode, 四栏) 平铺编码；失败回包同样占位写出，保持结构固定。
     */
    public static final StreamCodec<FriendlyByteBuf, MaidPersonaSaveResponsePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUUID(payload.requestId());
                        buf.writeVarInt(payload.entityId());
                        buf.writeUUID(payload.maidUuid());
                        buf.writeBoolean(payload.success());
                        buf.writeUtf(payload.resultCode(), MAX_CODE_LENGTH);
                        buf.writeUtf(payload.confirmed().mode().tag(), MAX_CODE_LENGTH);
                        buf.writeUtf(payload.confirmed().draft().identity(), MaidPersonaDraft.MAX_TOTAL_LENGTH);
                        buf.writeUtf(payload.confirmed().draft().personality(), MaidPersonaDraft.MAX_TOTAL_LENGTH);
                        buf.writeUtf(payload.confirmed().draft().background(), MaidPersonaDraft.MAX_TOTAL_LENGTH);
                        buf.writeUtf(payload.confirmed().draft().hobbies(), MaidPersonaDraft.MAX_TOTAL_LENGTH);
                    },
                    buf -> {
                        UUID requestId = buf.readUUID();
                        int entityId = buf.readVarInt();
                        UUID maidUuid = buf.readUUID();
                        boolean success = buf.readBoolean();
                        String resultCode = buf.readUtf(MAX_CODE_LENGTH);
                        String mode = buf.readUtf(MAX_CODE_LENGTH);
                        MaidPersonaDraft draft = new MaidPersonaDraft(
                                buf.readUtf(MaidPersonaDraft.MAX_TOTAL_LENGTH),
                                buf.readUtf(MaidPersonaDraft.MAX_TOTAL_LENGTH),
                                buf.readUtf(MaidPersonaDraft.MAX_TOTAL_LENGTH),
                                buf.readUtf(MaidPersonaDraft.MAX_TOTAL_LENGTH));
                        return new MaidPersonaSaveResponsePayload(requestId, entityId, maidUuid, success,
                                resultCode, new MaidPersonaConfig(MaidPersonaMode.fromTag(mode), draft));
                    });

    /** 成功回包：确认配置即服务端实际写入的配置 */
    public static MaidPersonaSaveResponsePayload ok(UUID requestId, int entityId, UUID maidUuid,
                                                    MaidPersonaConfig confirmed) {
        return new MaidPersonaSaveResponsePayload(requestId, entityId, maidUuid, true, RESULT_OK, confirmed);
    }

    /** 失败回包：confirmed 为占位默认配置，客户端不得使用 */
    public static MaidPersonaSaveResponsePayload failure(UUID requestId, int entityId, UUID maidUuid,
                                                         String resultCode) {
        return new MaidPersonaSaveResponsePayload(requestId, entityId, maidUuid, false, resultCode,
                MaidPersonaConfig.DEFAULT);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
