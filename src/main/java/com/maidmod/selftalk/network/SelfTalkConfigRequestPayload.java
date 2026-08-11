package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：客户端请求当前玩家自话设置（含管理员是否允许玩家配置）。
 *
 * @param maidUuid 打开设置界面的女仆 UUID，服务端据此返回该女仆的单只有效值
 */
public record SelfTalkConfigRequestPayload(UUID maidUuid) implements CustomPacketPayload {

    public static final Type<SelfTalkConfigRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "config_request"));

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    public static final StreamCodec<FriendlyByteBuf, SelfTalkConfigRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUID_STREAM_CODEC, SelfTalkConfigRequestPayload::maidUuid,
                    SelfTalkConfigRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
