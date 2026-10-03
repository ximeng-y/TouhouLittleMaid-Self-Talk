package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.EnvironmentContextOption;
import com.maidmod.selftalk.HistoryContextMode;
import com.maidmod.selftalk.EnvironmentContextSettings;
import com.maidmod.selftalk.PlayerSettingsStore;
import com.maidmod.selftalk.client.SelfTalkPlayerSettingsClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C：环境上下文设置的权威全量快照。
 * <p>
 * 每次响应都含完整 32 项（含禁用项）：<b>不隐藏</b>不可用条目，界面只置灰并给出禁用说明。
 * 回传的 mode 是「已存偏好补默认值」的结果，不是被管理员强制覆盖后的有效模式——
 * 玩家保存的选择必须在功能恢复后原样生效。
 *
 * @param sessionId    浮层会话 id（客户端据此丢弃关闭后或旧会话的迟到回包）
 * @param seq          对应请求的序号
 * @param adminEnabled 管理员是否允许玩家配置（{@code PLAYER_OPTION_ENABLED}）。
 *                     为 false 时界面整页禁用，禁用原因优先显示「玩家自定义配置被禁用」，
 *                     因此不必逐行改写 availability
 * @param naturalLanguageEnabled 「环境信息自然语言化」的<b>已存偏好</b>，不是管理员门控后的有效值——
 *                     与 32 项模式同口径：管理员禁用时界面要能显示玩家原来的选择，
 *                     功能恢复后原样生效
 * @param historyContextModeId 历史上下文模式的<b>已存偏好</b>存储值（口径同上：不回传管理员门控后的有效值）
 * @param rows         32 行状态（顺序即目录顺序）。两个开关都不走这里，不伪装成第 33、34 项
 */
public class EnvironmentContextConfigResponseMessage {

    /**
     * 单行状态。
     *
     * @param contextKey   目录条目 key
     * @param modeId       玩家已存偏好（已补默认值）的模式存储值
     * @param availability {@link EnvironmentContextSettings.Availability} 协议值
     */
    public record Row(String contextKey, String modeId, byte availability) {
    }

    private final UUID sessionId;
    private final long seq;
    private final boolean adminEnabled;
    private final boolean naturalLanguageEnabled;
    private final String historyContextModeId;
    private final List<Row> rows;

    public EnvironmentContextConfigResponseMessage(UUID sessionId, long seq, boolean adminEnabled,
                                                   boolean naturalLanguageEnabled, String historyContextModeId,
                                                   List<Row> rows) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.adminEnabled = adminEnabled;
        this.naturalLanguageEnabled = naturalLanguageEnabled;
        this.historyContextModeId = historyContextModeId;
        this.rows = rows;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public long getSeq() {
        return seq;
    }

    public boolean isAdminEnabled() {
        return adminEnabled;
    }

    public boolean isNaturalLanguageEnabled() {
        return naturalLanguageEnabled;
    }

    public String getHistoryContextModeId() {
        return historyContextModeId;
    }

    public List<Row> getRows() {
        return rows;
    }

    /**
     * 组装权威快照（服务端主线程调用）。
     * <p>
     * 行序即目录序；每行取玩家的已存偏好（缺省补目录默认值）与实际可用性，
     * 两者互不影响——管理员禁用不清空偏好，偏好也不改变可用性。
     * 总开关同样回传玩家的<b>已存偏好</b>，管理员禁用时也不改写。
     */
    public static EnvironmentContextConfigResponseMessage snapshot(MinecraftServer server, UUID playerUuid,
                                                                   UUID sessionId, long seq) {
        List<Row> rows = new ArrayList<>(EnvironmentContextOption.ALL.size());
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            rows.add(new Row(option.key(),
                    PlayerSettingsStore.getEnvironmentContextMode(server, playerUuid, option).id(),
                    EnvironmentContextSettings.availabilityOf(option).id()));
        }
        return new EnvironmentContextConfigResponseMessage(
                sessionId, seq, Config.PLAYER_OPTION_ENABLED.get(),
                PlayerSettingsStore.isEnvironmentContextNaturalLanguageEnabled(server, playerUuid),
                PlayerSettingsStore.getHistoryContextMode(server, playerUuid).id(), rows);
    }

    /**
     * 校验快照形状：数量必须恰为 {@link EnvironmentContextOption#ALL} 的长度，
     * key 不得重复、缺失或落在目录外。
     * <p>
     * 发现异常直接丢弃整包——部分接受的快照会让界面显示的偏好与存档不一致。
     */
    public boolean isValidShape() {
        if (rows == null || rows.size() != EnvironmentContextOption.ALL.size()) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (Row row : rows) {
            if (row == null || EnvironmentContextOption.byKey(row.contextKey()) == null
                    || !seen.add(row.contextKey())) {
                return false;
            }
        }
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            if (!seen.contains(option.key())) {
                return false;
            }
        }
        return true;
    }

    public static void encode(EnvironmentContextConfigResponseMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.sessionId);
        buf.writeVarLong(msg.seq);
        buf.writeBoolean(msg.adminEnabled);
        // 总开关排在 adminEnabled 之后、行表之前：两个布尔字段与 32 项行序无关
        buf.writeBoolean(msg.naturalLanguageEnabled);
        // 历史上下文模式与前两个字段同属标题区状态，排在行表之前
        buf.writeUtf(msg.historyContextModeId == null ? HistoryContextMode.FULL.id() : msg.historyContextModeId,
                HistoryContextModeSetMessage.MAX_MODE_LENGTH);
        // 数量先校验再写入：解码侧只会读到 0~32 行，超量包在编码侧就不可能产生
        int size = msg.rows == null ? 0 : msg.rows.size();
        if (size != EnvironmentContextOption.ALL.size()) {
            buf.writeByte(0);
            return;
        }
        buf.writeByte(size);
        for (Row row : msg.rows) {
            buf.writeUtf(row.contextKey(), EnvironmentContextConfigSetMessage.MAX_KEY_LENGTH);
            buf.writeUtf(row.modeId(), EnvironmentContextConfigSetMessage.MAX_MODE_LENGTH);
            buf.writeByte(row.availability());
        }
    }

    /** 解码：先读数量并按目录长度校验，再分配与读取；数量不符直接返回空行列表（由 isValidShape 拒绝） */
    public static EnvironmentContextConfigResponseMessage decode(FriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        long seq = buf.readVarLong();
        boolean adminEnabled = buf.readBoolean();
        boolean naturalLanguageEnabled = buf.readBoolean();
        String historyContextModeId = buf.readUtf(HistoryContextModeSetMessage.MAX_MODE_LENGTH);
        int size = buf.readByte();
        if (size != EnvironmentContextOption.ALL.size()) {
            return new EnvironmentContextConfigResponseMessage(
                    sessionId, seq, adminEnabled, naturalLanguageEnabled, historyContextModeId, List.of());
        }
        List<Row> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(new Row(buf.readUtf(EnvironmentContextConfigSetMessage.MAX_KEY_LENGTH),
                    buf.readUtf(EnvironmentContextConfigSetMessage.MAX_MODE_LENGTH),
                    buf.readByte()));
        }
        return new EnvironmentContextConfigResponseMessage(
                sessionId, seq, adminEnabled, naturalLanguageEnabled, historyContextModeId, rows);
    }

    /** 客户端分发：继续沿用现有隔离方式，避免专用服务端加载 Screen 相关类 */
    public static void handle(EnvironmentContextConfigResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SelfTalkPlayerSettingsClient.onEnvironmentContextConfigResponse(msg)));
        ctx.get().setPacketHandled(true);
    }
}
