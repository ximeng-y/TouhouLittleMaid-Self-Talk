package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：请求环境上下文设置的全量权威快照。
 * <p>
 * 包内<b>不携带</b> player UUID 或 maid UUID：这是玩家自己的全局偏好，服务端一律用连接上的
 * {@code ServerPlayer} 身份读写，不需要当前女仆加载、也不做单只归属检查。
 *
 * @param sessionId 本次浮层会话 id（关闭重开即换新，用于丢弃迟到回包）
 * @param seq       同一会话内递增的请求序号
 */
public record EnvironmentContextConfigRequestPayload(UUID sessionId, long seq) implements CustomPacketPayload {

    public static final Type<EnvironmentContextConfigRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID,
                    "environment_context_config_request"));

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    public static final StreamCodec<FriendlyByteBuf, EnvironmentContextConfigRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUID_STREAM_CODEC, EnvironmentContextConfigRequestPayload::sessionId,
                    ByteBufCodecs.VAR_LONG, EnvironmentContextConfigRequestPayload::seq,
                    EnvironmentContextConfigRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
