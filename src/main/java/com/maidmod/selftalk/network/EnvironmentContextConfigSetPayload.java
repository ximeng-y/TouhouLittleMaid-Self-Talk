package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S：提交环境上下文单项模式（玩家全局偏好，作用于该玩家名下全部女仆）。
 * <p>
 * 发送的是<b>目标绝对模式</b>而不是「切换一次」命令：丢包／乱序时不会把模式切到意料之外的状态，
 * 客户端也无需依赖本地推断链条。包内不携带 player UUID 或 maid UUID（语义上就没有这两个维度）。
 *
 * @param sessionId  浮层会话 id
 * @param seq        该会话内的请求序号
 * @param contextKey 目录条目 key，最长 128 字符
 * @param modeId     模式存储值（{@code random}/{@code always}/{@code never}），最长 16 字符
 */
public record EnvironmentContextConfigSetPayload(UUID sessionId, long seq, String contextKey, String modeId)
        implements CustomPacketPayload {

    public static final Type<EnvironmentContextConfigSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID,
                    "environment_context_config_set"));

    /** 目录 key 长度上限（编解码先校验，防超长字符串撑包） */
    public static final int MAX_KEY_LENGTH = 128;
    /** 模式 id 长度上限 */
    public static final int MAX_MODE_LENGTH = 16;

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    /** 限长字符串：编码前截断，解码按上限读取，两侧都无法产生超长串 */
    private static StreamCodec<FriendlyByteBuf, String> boundedString(int maxLength) {
        return StreamCodec.of(
                (buf, value) -> buf.writeUtf(value == null ? "" : value, maxLength),
                buf -> buf.readUtf(maxLength));
    }

    public static final StreamCodec<FriendlyByteBuf, EnvironmentContextConfigSetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUID_STREAM_CODEC, EnvironmentContextConfigSetPayload::sessionId,
                    ByteBufCodecs.VAR_LONG, EnvironmentContextConfigSetPayload::seq,
                    boundedString(MAX_KEY_LENGTH), EnvironmentContextConfigSetPayload::contextKey,
                    boundedString(MAX_MODE_LENGTH), EnvironmentContextConfigSetPayload::modeId,
                    EnvironmentContextConfigSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
