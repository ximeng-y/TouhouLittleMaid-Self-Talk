package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * C2S：客户端提交自定义 Prompt 设置。一个包三种写入意图，解码后服务端做互斥校验
 * （prompt 与 override 二选一；override 意图不接受女仆维度），一次只表达一种写入。
 *
 * <ul>
 *   <li>maidUuid 空 + prompt 有值 → 写全局段 Prompt；</li>
 *   <li>maidUuid 有值 + prompt 有值 → 写该女仆单只段 Prompt（服务端校验归属）；</li>
 *   <li>override 有值（maidUuid 必须为空）→ 写「全局覆盖单只」开关。</li>
 * </ul>
 */
public record CustomPromptConfigSetPayload(Optional<UUID> maidUuid, Optional<String> prompt,
                                           Optional<Boolean> override) implements CustomPacketPayload {

    public static final Type<CustomPromptConfigSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "custom_prompt_config_set"));

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    /** 流侧限长 600；服务端再做 500 字符截断 + 控制字符清洗 */
    private static final StreamCodec<ByteBuf, String> PROMPT_CODEC = ByteBufCodecs.stringUtf8(600);

    public static final StreamCodec<FriendlyByteBuf, CustomPromptConfigSetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.optional(UUID_STREAM_CODEC), CustomPromptConfigSetPayload::maidUuid,
                    ByteBufCodecs.optional(PROMPT_CODEC), CustomPromptConfigSetPayload::prompt,
                    ByteBufCodecs.optional(ByteBufCodecs.BOOL), CustomPromptConfigSetPayload::override,
                    CustomPromptConfigSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
