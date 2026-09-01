package com.maidmod.selftalk.network;

import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：服务端返回玩家自定义 Prompt 设置。
 *
 * @param adminEnabled    管理员是否允许玩家配置（false 时客户端 Prompt 控件置灰）
 * @param globalPrompt    全局段 Prompt（全部女仆，未填写为空串）
 * @param maidPrompt      请求的女仆单只段 Prompt（未填写为空串）
 * @param overrideEnabled 「全局配置覆盖单只」开关
 */
public class CustomPromptConfigResponseMessage {

    /** 服务端存档上限 500，流上限放宽到 600 兜底（防超大包） */
    public static final int MAX_WIRE_LENGTH = 600;

    private final boolean adminEnabled;
    private final String globalPrompt;
    private final String maidPrompt;
    private final boolean overrideEnabled;

    public CustomPromptConfigResponseMessage(boolean adminEnabled, String globalPrompt,
                                             String maidPrompt, boolean overrideEnabled) {
        this.adminEnabled = adminEnabled;
        this.globalPrompt = globalPrompt;
        this.maidPrompt = maidPrompt;
        this.overrideEnabled = overrideEnabled;
    }

    public boolean isAdminEnabled() {
        return adminEnabled;
    }

    public String getGlobalPrompt() {
        return globalPrompt;
    }

    public String getMaidPrompt() {
        return maidPrompt;
    }

    public boolean isOverrideEnabled() {
        return overrideEnabled;
    }

    public static void encode(CustomPromptConfigResponseMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.adminEnabled);
        buf.writeUtf(msg.globalPrompt, MAX_WIRE_LENGTH);
        buf.writeUtf(msg.maidPrompt, MAX_WIRE_LENGTH);
        buf.writeBoolean(msg.overrideEnabled);
    }

    public static CustomPromptConfigResponseMessage decode(FriendlyByteBuf buf) {
        boolean adminEnabled = buf.readBoolean();
        String globalPrompt = buf.readUtf(MAX_WIRE_LENGTH);
        String maidPrompt = buf.readUtf(MAX_WIRE_LENGTH);
        boolean overrideEnabled = buf.readBoolean();
        return new CustomPromptConfigResponseMessage(adminEnabled, globalPrompt, maidPrompt, overrideEnabled);
    }

    public static void handle(CustomPromptConfigResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SelfTalkPlayerSettingsClient.onCustomPromptConfigResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
