package com.maidmod.selftalk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 1.1.2 升级迁移：玩家设置从 persistentData（PlayerPersisted 子标签）迁移到 overworld SavedData。
 * <p>
 * 触发时点：玩家登录（服务端主线程），幂等常驻——
 * <ul>
 *   <li>SavedData 目标组无该玩家条目 → 拷贝旧值（旧值恰为缺省时不落盘，与"无键=缺省"一致）；</li>
 *   <li>SavedData 目标组已有条目（已迁移过或迁移后已修改）→ 跳过拷贝，新值以 SavedData 为准；</li>
 *   <li>两种情况下都删除 PlayerPersisted 子标签中的旧 key（随玩家下次存档从 player.dat 消失）。</li>
 * </ul>
 * 迁移只处理自话/互聊两组（睡觉时安静为新增功能，无旧数据源，一律走缺省）。
 * 崩溃窗口：SavedData 与 player.dat 保存时机不同步——若旧 key 剔除后、SavedData 落盘前崩溃，
 * 设置将回落到缺省且旧数据不可恢复（窄窗口，可接受）；正常保存顺序下无重复数据。
 */
public final class SelfTalkMigration {

    /** 旧版（1.1.1 及更早）persistentData 中的设置 key（仅迁移读删用；旧类 PlayerSettingsStorage 已随迁移删除） */
    private static final String OLD_ENABLED = "maid_self_talk:enabled";
    private static final String OLD_DISABLED_MAIDS = "maid_self_talk:disabled_maids";
    private static final String OLD_INTER_CHAT_ENABLED = "maid_self_talk:inter_chat_enabled";
    private static final String OLD_INTER_CHAT_DISABLED_MAIDS = "maid_self_talk:inter_chat_disabled_maids";

    private SelfTalkMigration() {
    }

    /** 玩家登录时调用（服务端主线程） */
    public static void migrate(ServerPlayer player) {
        MinecraftServer server = player.server;
        UUID uuid = player.getUUID();
        CompoundTag sub = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);

        // ==== 自话 ====
        if (!PlayerSettingsStore.hasSelfTalkData(server, uuid)) {
            if (sub.contains(OLD_ENABLED)) {
                PlayerSettingsStore.setSelfTalkEnabled(server, uuid, sub.getBoolean(OLD_ENABLED));
                copyMaidList(server, PlayerSettingsStore.selfTalkMaidTable(server), uuid, sub, OLD_DISABLED_MAIDS);
            } else if (sub.contains(OLD_DISABLED_MAIDS)) {
                copyMaidList(server, PlayerSettingsStore.selfTalkMaidTable(server), uuid, sub, OLD_DISABLED_MAIDS);
            }
        }

        // ==== 互聊 ====
        if (!PlayerSettingsStore.hasInterChatData(server, uuid)) {
            if (sub.contains(OLD_INTER_CHAT_ENABLED)) {
                PlayerSettingsStore.setInterChatEnabled(server, uuid, sub.getBoolean(OLD_INTER_CHAT_ENABLED));
                copyMaidList(server, PlayerSettingsStore.interChatMaidTable(server), uuid, sub, OLD_INTER_CHAT_DISABLED_MAIDS);
            } else if (sub.contains(OLD_INTER_CHAT_DISABLED_MAIDS)) {
                copyMaidList(server, PlayerSettingsStore.interChatMaidTable(server), uuid, sub, OLD_INTER_CHAT_DISABLED_MAIDS);
            }
        }

        // 剔除旧 key（无论是否发生拷贝：已迁移过则仅清洗残留）
        sub.remove(OLD_ENABLED);
        sub.remove(OLD_DISABLED_MAIDS);
        sub.remove(OLD_INTER_CHAT_ENABLED);
        sub.remove(OLD_INTER_CHAT_DISABLED_MAIDS);
    }

    /** 旧名单（女仆 UUID 字符串列表）并入 SavedData 名单表 */
    private static void copyMaidList(MinecraftServer server, Map<String, Map<String, Boolean>> table,
                                     UUID playerUuid, CompoundTag sub, String oldKey) {
        ListTag oldList = sub.getList(oldKey, Tag.TAG_STRING);
        if (oldList.isEmpty()) {
            return;
        }
        String playerKey = playerUuid.toString();
        Map<String, Boolean> inner = new HashMap<>(table.getOrDefault(playerKey, java.util.Map.of()));
        for (Tag tag : oldList) {
            inner.put(((StringTag) tag).getAsString(), false);
        }
        table.put(playerKey, inner);
        PlayerSettingsStore.markDirty(server);
    }
}
