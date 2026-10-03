package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：提交「环境信息自然语言化」玩家级总开关（实验性，默认关闭）。
 * <p>
 * 发送的是<b>目标绝对布尔值</b>而不是「切换一次」命令：丢包／乱序时不会把开关切到意料之外的状态，
 * 客户端也无需依赖本地推断链条。包内<b>不携带</b>玩家或女仆 UUID——语义上就没有这两个维度，
 * 服务端一律用连接上的 {@code ServerPlayer} 身份读写。
 * <p>
 * 与 32 项设置共用同一个浮层会话：{@code sessionId} 与递增 {@code seq} 都取自当前浮层会话，
 * 因此旧会话或过期响应不会覆盖新状态。
 *
 * @param sessionId 浮层会话 id
 * @param seq       该会话内的请求序号
 * @param enabled   目标绝对状态
 */
public record EnvironmentContextNaturalLanguageSetPayload(UUID sessionId, long seq, boolean enabled)
        implements CustomPacketPayload {

    public static final Type<EnvironmentContextNaturalLanguageSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID,
                    "environment_context_natural_language_set"));

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    public static final StreamCodec<FriendlyByteBuf, EnvironmentContextNaturalLanguageSetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUID_STREAM_CODEC, EnvironmentContextNaturalLanguageSetPayload::sessionId,
                    ByteBufCodecs.VAR_LONG, EnvironmentContextNaturalLanguageSetPayload::seq,
                    ByteBufCodecs.BOOL, EnvironmentContextNaturalLanguageSetPayload::enabled,
                    EnvironmentContextNaturalLanguageSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
