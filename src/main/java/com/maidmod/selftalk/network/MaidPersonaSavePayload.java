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
 * C2S：保存某只女仆的人设配置（意图标记 + 四栏内容）。
 * <p>
 * confirmMode 区分保存意图：{@code true} = 显式保存（页面「保存」/「保存并退出」），
 * 服务端采用请求中的模式与四栏；{@code false} = 仅内容保存（离页自动保存），
 * 请求中的模式不具写入效力，服务端保留处理请求当时的实际模式、只并入新四栏。
 * <p>
 * 四个栏位按平台原生有界编解码直传原文：不做截断、控制字符过滤与段标签清洗
 * （与自定义 Prompt 的写入清洗策略刻意不同——人设内容原样保留，界面与存档保留玩家输入）。
 * 合计长度校验在服务端做（超限回 {@code too_long}，不写入），流侧上限按单栏放宽，
 * 让「单栏合法但合计超限」的请求能正常解码并被业务拒绝，而不是在解码期直接断开。
 * <p>
 * 同时携带女仆 UUID：实体 id 只在单个连接内、且只在发送时刻的维度内有效，
 * 服务端必须同时核对 id 与 UUID 才能确认「收到请求的这只，就是玩家当初编辑的那只」。
 *
 * @param requestId   本次提交的请求 id（回包按它匹配，避免迟到响应被当作本次结果）
 * @param entityId    女仆实体 id（服务端在玩家当前维度查找该实体）
 * @param maidUuid    女仆 UUID（与 entityId 双重校验，防实体 id 复用/换维度后错改别的女仆）
 * @param confirmMode 保存意图：true = 显式保存（确认模式与四栏），false = 仅内容保存（模式不写入）
 * @param mode        人设模式标签（仅显式保存时生效；{@link MaidPersonaMode#tag()}，未知值服务端按原版处理）
 */
public record MaidPersonaSavePayload(UUID requestId, int entityId, UUID maidUuid, boolean confirmMode,
                                     String mode, String identity, String personality, String background,
                                     String hobbies)
        implements CustomPacketPayload {

    /** 单栏流侧上限：等于四栏合计上限（四栏各占满也仍可解码，由服务端按合计判定超限） */
    public static final int MAX_WIRE_FIELD_LENGTH = MaidPersonaDraft.MAX_TOTAL_LENGTH;

    /** 模式字段流侧上限：固定短标签，留出余量 */
    public static final int MAX_MODE_LENGTH = 32;

    public static final Type<MaidPersonaSavePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "maid_persona_save"));

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    /**
     * 手写编解码：字段数已超出 {@code StreamCodec.composite} 的分量上限（6 个），
     * 按固定顺序逐字段读写，与既有 UUID 编解码同一写法。
     */
    public static final StreamCodec<FriendlyByteBuf, MaidPersonaSavePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.requestId());
                buf.writeVarInt(payload.entityId());
                buf.writeUUID(payload.maidUuid());
                buf.writeBoolean(payload.confirmMode());
                buf.writeUtf(payload.mode(), MAX_MODE_LENGTH);
                buf.writeUtf(payload.identity(), MAX_WIRE_FIELD_LENGTH);
                buf.writeUtf(payload.personality(), MAX_WIRE_FIELD_LENGTH);
                buf.writeUtf(payload.background(), MAX_WIRE_FIELD_LENGTH);
                buf.writeUtf(payload.hobbies(), MAX_WIRE_FIELD_LENGTH);
            },
            buf -> new MaidPersonaSavePayload(buf.readUUID(), buf.readVarInt(), buf.readUUID(),
                    buf.readBoolean(), buf.readUtf(MAX_MODE_LENGTH), buf.readUtf(MAX_WIRE_FIELD_LENGTH),
                    buf.readUtf(MAX_WIRE_FIELD_LENGTH), buf.readUtf(MAX_WIRE_FIELD_LENGTH),
                    buf.readUtf(MAX_WIRE_FIELD_LENGTH)));

    /** 本次提交的四栏快照（不可变值对象） */
    public MaidPersonaDraft toDraft() {
        return new MaidPersonaDraft(identity, personality, background, hobbies);
    }

    /** 本次提交的完整配置快照：仅显式保存时由服务端整份采用（内容保存只用 toDraft） */
    public MaidPersonaConfig toConfig() {
        return new MaidPersonaConfig(MaidPersonaMode.fromTag(mode), toDraft());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
