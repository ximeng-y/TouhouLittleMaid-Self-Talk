package com.maidmod.selftalk.network;

import com.maidmod.selftalk.Config;
import com.maidmod.selftalk.EnvironmentContextOption;
import com.maidmod.selftalk.EnvironmentContextSettings;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.PlayerSettingsStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * S2C：环境上下文设置的权威全量快照。
 * <p>
 * 每次响应都含完整 32 项（含禁用项）：<b>不隐藏</b>不可用条目，界面只置灰并给出禁用说明。
 * 回传的 mode 是「已存偏好补默认值」的结果，不是被管理员强制覆盖后的有效模式——
 * 玩家保存的选择必须在功能恢复后原样生效。
 *
 * @param sessionId            浮层会话 id（客户端据此丢弃关闭后或旧会话的迟到回包）
 * @param seq                  对应请求的序号
 * @param adminEnabled         管理员是否允许玩家配置（{@code PLAYER_OPTION_ENABLED}）。
 *                             为 false 时界面整页禁用，禁用原因优先显示「玩家自定义配置被禁用」，
 *                             因此不必逐行改写 availability
 * @param naturalLanguageEnabled 「环境信息自然语言化」的<b>已存偏好</b>，不是管理员门控后的有效值——
 *                             与 32 项模式同口径：管理员禁用时界面要能显示玩家原来的选择，
 *                             功能恢复后原样生效
 * @param historyContextModeId 「历史上下文模式」的<b>已存偏好</b>存储值，口径同上。
 *                             它是浮层标题区的独立控件，<b>不</b>伪装成环境目录中的第 33 项
 * @param rows                 32 行状态（顺序即目录顺序）。两个标题区开关与模式都不走这里
 */
public record EnvironmentContextConfigResponsePayload(UUID sessionId, long seq, boolean adminEnabled,
                                                      boolean naturalLanguageEnabled,
                                                      String historyContextModeId, List<Row> rows)
        implements CustomPacketPayload {

    /**
     * 单行状态。
     *
     * @param contextKey   目录条目 key
     * @param modeId       玩家已存偏好（已补默认值）的模式存储值
     * @param availability {@link EnvironmentContextSettings.Availability} 协议值
     */
    public record Row(String contextKey, String modeId, byte availability) {

        private static final StreamCodec<FriendlyByteBuf, String> KEY_CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value == null ? "" : value,
                        EnvironmentContextConfigSetPayload.MAX_KEY_LENGTH),
                buf -> buf.readUtf(EnvironmentContextConfigSetPayload.MAX_KEY_LENGTH));

        private static final StreamCodec<FriendlyByteBuf, String> MODE_CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value == null ? "" : value,
                        EnvironmentContextConfigSetPayload.MAX_MODE_LENGTH),
                buf -> buf.readUtf(EnvironmentContextConfigSetPayload.MAX_MODE_LENGTH));

        public static final StreamCodec<FriendlyByteBuf, Row> STREAM_CODEC = StreamCodec.composite(
                KEY_CODEC, Row::contextKey,
                MODE_CODEC, Row::modeId,
                ByteBufCodecs.BYTE, Row::availability,
                Row::new);
    }

    public static final Type<EnvironmentContextConfigResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MaidSelfTalkMod.MODID,
                    "environment_context_config_response"));

    /** UUID 手写编解码（1.21.1 的 ByteBufCodecs 无 UUID 常量，readUUID/writeUUID 在 FriendlyByteBuf 上） */
    private static final StreamCodec<FriendlyByteBuf, UUID> UUID_STREAM_CODEC = StreamCodec.of(
            (buf, uuid) -> buf.writeUUID(uuid),
            buf -> buf.readUUID());

    /**
     * 行列表编解码：绑定上限即目录长度，解码侧因此不可能收到超量行；
     * 形状校验（不重复、不缺失、不越目录）由 {@link #isValidShape()} 在收到后补做。
     */
    private static final StreamCodec<FriendlyByteBuf, List<Row>> ROWS_STREAM_CODEC =
            Row.STREAM_CODEC.apply(ByteBufCodecs.list(EnvironmentContextOption.ALL.size()));

    /** 历史上下文模式存储值的编解码（长度上限与 Set 包同口径） */
    private static final StreamCodec<FriendlyByteBuf, String> HISTORY_MODE_CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUtf(value == null ? "" : value,
                    HistoryContextModeSetPayload.MAX_MODE_LENGTH),
            buf -> buf.readUtf(HistoryContextModeSetPayload.MAX_MODE_LENGTH));

    public static final StreamCodec<FriendlyByteBuf, EnvironmentContextConfigResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUID_STREAM_CODEC, EnvironmentContextConfigResponsePayload::sessionId,
                    ByteBufCodecs.VAR_LONG, EnvironmentContextConfigResponsePayload::seq,
                    ByteBufCodecs.BOOL, EnvironmentContextConfigResponsePayload::adminEnabled,
                    ByteBufCodecs.BOOL, EnvironmentContextConfigResponsePayload::naturalLanguageEnabled,
                    HISTORY_MODE_CODEC, EnvironmentContextConfigResponsePayload::historyContextModeId,
                    ROWS_STREAM_CODEC, EnvironmentContextConfigResponsePayload::rows,
                    EnvironmentContextConfigResponsePayload::new);

    /**
     * 组装权威快照（服务端主线程调用）。
     * <p>
     * 行序即目录序；每行取玩家的已存偏好（缺省补目录默认值）与实际可用性，
     * 两者互不影响——管理员禁用不清空偏好，偏好也不改变可用性。
     * 两个标题区控件同样回传玩家的<b>已存偏好</b>，管理员禁用时也不改写。
     */
    public static EnvironmentContextConfigResponsePayload snapshot(MinecraftServer server, UUID playerUuid,
                                                                   UUID sessionId, long seq) {
        List<Row> rows = new ArrayList<>(EnvironmentContextOption.ALL.size());
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            rows.add(new Row(option.key(),
                    PlayerSettingsStore.getEnvironmentContextMode(server, playerUuid, option).id(),
                    EnvironmentContextSettings.availabilityOf(option).id()));
        }
        return new EnvironmentContextConfigResponsePayload(
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

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
