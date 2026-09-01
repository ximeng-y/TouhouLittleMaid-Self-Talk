package com.maidmod.selftalk.network;

import com.maidmod.selftalk.MaidSelfTalkMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：服务端返回玩家自定义 Prompt 设置。
 *
 * @param adminEnabled    管理员是否允许玩家配置（false 时客户端 Prompt 控件置灰）
 * @param globalPrompt    全局段 Prompt（全部女仆，未填写为空串）
 * @param maidPrompt      请求的女仆单只段 Prompt（未填写为空串）
 * @param overrideEnabled 「全局配置覆盖单只」开关
 */
public record CustomPromptConfigResponsePayload(boolean adminEnabled, String globalPrompt,
                                                String maidPrompt, boolean overrideEnabled)
        implements CustomPacketPayload {

    public static final Type<CustomPromptConfigResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID, "custom_prompt_config_response"));

    /** 服务端存档上限 500，流上限放宽到 600 兜底（防超大包：超限直接断连由 netty 层处理） */
    private static final StreamCodec<ByteBuf, String> PROMPT_CODEC = ByteBufCodecs.stringUtf8(600);

    public static final StreamCodec<ByteBuf, CustomPromptConfigResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, CustomPromptConfigResponsePayload::adminEnabled,
                    PROMPT_CODEC, CustomPromptConfigResponsePayload::globalPrompt,
                    PROMPT_CODEC, CustomPromptConfigResponsePayload::maidPrompt,
                    ByteBufCodecs.BOOL, CustomPromptConfigResponsePayload::overrideEnabled,
                    CustomPromptConfigResponsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
