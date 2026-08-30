package com.maidmod.selftalk;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.attachment.AttachmentType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 1.1.2 升级迁移：玩家设置从玩家实体附件迁移到 overworld Level 附件。
 * <p>
 * 触发时点：玩家登录（服务端主线程），幂等常驻——
 * <ul>
 *   <li>Level 目标键不存在 → 拷贝旧值（旧值恰为缺省时不落盘，与"无键=缺省"一致）；</li>
 *   <li>Level 目标键已存在（已迁移过或迁移后已修改）→ 跳过拷贝，新值以 Level 为准；</li>
 *   <li>两种情况下都 removeData 剔除旧玩家实体附件（随玩家下次存档从 player.dat 消失）。</li>
 * </ul>
 * 迁移只处理自话/互聊两组（睡觉时安静为新增功能，无旧数据源，一律走缺省）。
 * 崩溃窗口：Level 与 player.dat 保存时机不同步最多造成"设置回落缺省"，下次登录重新迁移,无重复数据。
 */
public final class SelfTalkMigration {

    private SelfTalkMigration() {
    }

    /** 玩家登录时调用（服务端主线程） */
    public static void migrate(ServerPlayer player) {
        MinecraftServer server = player.server;
        UUID uuid = player.getUUID();
        String key = uuid.toString();

        // ==== 自话 ====
        if (player.hasData(SelfTalkAttachments.SELF_TALK_ENABLED)) {
            boolean oldEnabled = player.getExistingData(SelfTalkAttachments.SELF_TALK_ENABLED).orElse(true);
            if (!hasGlobalKey(server, SelfTalkAttachments.LEVEL_SELF_TALK_ENABLED, key)) {
                PlayerSettingsStore.setSelfTalkEnabled(server, uuid, oldEnabled);
            }
        }
        if (player.hasData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES)) {
            if (!hasMaidKey(server, SelfTalkAttachments.LEVEL_SELF_TALK_MAID_OVERRIDES, key)) {
                mergeMaidOverrides(server, SelfTalkAttachments.LEVEL_SELF_TALK_MAID_OVERRIDES,
                        uuid, player.getExistingData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES).orElse(Map.of()));
            }
        }

        // ==== 互聊 ====
        if (player.hasData(SelfTalkAttachments.INTER_CHAT_ENABLED)) {
            boolean oldEnabled = player.getExistingData(SelfTalkAttachments.INTER_CHAT_ENABLED).orElse(true);
            if (!hasGlobalKey(server, SelfTalkAttachments.LEVEL_INTER_CHAT_ENABLED, key)) {
                PlayerSettingsStore.setInterChatEnabled(server, uuid, oldEnabled);
            }
        }
        if (player.hasData(SelfTalkAttachments.INTER_CHAT_MAID_OVERRIDES)) {
            if (!hasMaidKey(server, SelfTalkAttachments.LEVEL_INTER_CHAT_MAID_OVERRIDES, key)) {
                mergeMaidOverrides(server, SelfTalkAttachments.LEVEL_INTER_CHAT_MAID_OVERRIDES,
                        uuid, player.getExistingData(SelfTalkAttachments.INTER_CHAT_MAID_OVERRIDES).orElse(Map.of()));
            }
        }

        // 剔除旧实体附件（无论是否发生拷贝：已迁移过则仅清洗残留）
        player.removeData(SelfTalkAttachments.SELF_TALK_ENABLED);
        player.removeData(SelfTalkAttachments.SELF_TALK_MAID_OVERRIDES);
        player.removeData(SelfTalkAttachments.INTER_CHAT_ENABLED);
        player.removeData(SelfTalkAttachments.INTER_CHAT_MAID_OVERRIDES);
    }

    /** Level 全局表是否已有该玩家键（有 = 已迁移/已修改，以 Level 为准） */
    private static boolean hasGlobalKey(MinecraftServer server, Supplier<AttachmentType<Map<String, Boolean>>> type, String playerKey) {
        return server.overworld().getExistingData(type)
                .map(m -> m.containsKey(playerKey))
                .orElse(false);
    }

    /** Level 两级名单表是否已有该玩家键 */
    private static boolean hasMaidKey(MinecraftServer server, Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> type, String playerKey) {
        return server.overworld().getExistingData(type)
                .map(m -> m.containsKey(playerKey))
                .orElse(false);
    }

    /** 把旧单只名单并入 Level 表（一次性整体写入，不逐条 setData） */
    private static void mergeMaidOverrides(MinecraftServer server,
                                           Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> type,
                                           UUID playerUuid, Map<String, Boolean> oldList) {
        Map<String, Map<String, Boolean>> outer = new HashMap<>(server.overworld().getData(type));
        String playerKey = playerUuid.toString();
        Map<String, Boolean> inner = new HashMap<>(outer.getOrDefault(playerKey, Map.of()));
        inner.putAll(oldList);
        if (inner.isEmpty()) {
            outer.remove(playerKey);
        } else {
            outer.put(playerKey, inner);
        }
        server.overworld().setData(type, outer);
    }
}
