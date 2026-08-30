package com.maidmod.selftalk.network;

import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：服务端返回玩家「睡觉时安静」设置。
 * <p>
 * 该设置不受管理员 Config.PLAYER_OPTION_ENABLED 控制，故无 admin 字段——
 * 客户端 UI 中此组按钮恒可操作。
 *
 * @param globalEnabled 玩家全局开关（自己所有女仆；缺省 true = 睡觉时安静）
 * @param maidEnabled   请求的女仆单只有效值（全局安静，或全局允许说话时单只名单含该女仆）
 */
public class SleepQuietConfigResponseMessage {

    private final boolean globalEnabled;
    private final boolean maidEnabled;

    public SleepQuietConfigResponseMessage(boolean globalEnabled, boolean maidEnabled) {
        this.globalEnabled = globalEnabled;
        this.maidEnabled = maidEnabled;
    }

    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public boolean isMaidEnabled() {
        return maidEnabled;
    }

    public static void encode(SleepQuietConfigResponseMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.globalEnabled);
        buf.writeBoolean(msg.maidEnabled);
    }

    public static SleepQuietConfigResponseMessage decode(FriendlyByteBuf buf) {
        return new SleepQuietConfigResponseMessage(buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(SleepQuietConfigResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SelfTalkPlayerSettingsClient.onSleepQuietConfigResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
