package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：请求预制复用设置的权威快照。
 * <p>
 * 包内<b>不携带</b> player UUID：玩家身份一律取连接上的 {@code ServerPlayer}。
 * {@code maidUuid} 为当前打开设置界面的女仆（可能为 null——仅查看全局默认时）；
 * 非空时服务端按玩家 UUID 读该女仆的三态，但<b>不做</b>归属校验（读路径无副作用，
 * 非本人女仆在存档中本就无条目，读到「跟随默认」）。
 *
 * @param sessionId 本次浮层会话 id（关闭重开即换新，用于丢弃迟到回包）
 * @param seq       同一会话内递增的请求序号
 * @param maidUuid  当前女仆 UUID，可为 null
 */
public record ReuseConfigRequestPayload(UUID sessionId, long seq, UUID maidUuid) implements CustomPacketPayload {

    public static final Type<ReuseConfigRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID,
                    "reuse_config_request"));

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    static final StreamCodec<FriendlyByteBuf, UUID> NULLABLE_UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> {
                buf.writeBoolean(uuid != null);
                if (uuid != null) {
                    buf.writeUUID(uuid);
                }
            },
            buf -> buf.readBoolean() ? buf.readUUID() : null);

    public static final StreamCodec<FriendlyByteBuf, ReuseConfigRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    NULLABLE_UUID_STREAM_CODEC, ReuseConfigRequestPayload::sessionId,
                    ByteBufCodecs.VAR_LONG, ReuseConfigRequestPayload::seq,
                    NULLABLE_UUID_STREAM_CODEC, ReuseConfigRequestPayload::maidUuid,
                    ReuseConfigRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
