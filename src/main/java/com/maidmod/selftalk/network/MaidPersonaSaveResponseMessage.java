package com.maidmod.selftalk.network;

import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C：结构化人设草稿保存的结果确认。
 * <p>
 * 每个可正常解码的保存请求都会收到一条回包（含失败），客户端据此区分「已保存」与「被拒绝」，
 * 不会把「包已发出」当成保存成功。
 */
public class MaidPersonaSaveResponseMessage {

    public static final String RESULT_OK = "ok";
    public static final String RESULT_RATE_LIMITED = "rate_limited";
    public static final String RESULT_NOT_LOADED = "not_loaded";
    public static final String RESULT_NOT_OWNER = "not_owner";
    public static final String RESULT_TOO_LONG = "too_long";
    public static final String RESULT_UNAVAILABLE = "unavailable";

    /** 结果码流侧上限：固定短码，留出余量即可 */
    public static final int MAX_CODE_LENGTH = 32;

    private final UUID requestId;
    private final boolean success;
    private final String resultCode;

    public MaidPersonaSaveResponseMessage(UUID requestId, boolean success, String resultCode) {
        this.requestId = requestId;
        this.success = success;
        this.resultCode = resultCode == null ? "" : resultCode;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getResultCode() {
        return resultCode;
    }

    public static void encode(MaidPersonaSaveResponseMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requestId);
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.resultCode, MAX_CODE_LENGTH);
    }

    public static MaidPersonaSaveResponseMessage decode(FriendlyByteBuf buf) {
        UUID requestId = buf.readUUID();
        boolean success = buf.readBoolean();
        String resultCode = buf.readUtf(MAX_CODE_LENGTH);
        return new MaidPersonaSaveResponseMessage(requestId, success, resultCode);
    }

    public static void handle(MaidPersonaSaveResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SelfTalkPlayerSettingsClient.onPersonaSaveResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
