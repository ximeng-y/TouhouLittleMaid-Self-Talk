package com.maidmod.selftalk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1.1.2 升级迁移：玩家设置从 persistentData（PlayerPersisted 子标签）迁移到 overworld SavedData。
 * <p>
 * 触发时点：玩家登录（服务端主线程），幂等常驻。按自话/互聊两组独立处理：
 * <ul>
 *   <li>目标组名单损坏（SavedData 里该玩家名单无法解析、已整条保留原始 NBT）→
 *       保留该组全部旧键，不迁移不删除；等显式调用该玩家的单只名单 setter
 *       以新输入替换损坏名单后再试；</li>
 *   <li>目标组已有有效数据（全局键、非空名单或显式接管标记）→ 保持「新组优先」语义，
 *       仅清除旧键（残留清洗）；接管标记随清洗解除——显式替换损坏名单后即使结果为空名单，
 *       也不得让旧值在下一次登录回灌；</li>
 *   <li>目标组无有效数据 → 先完整校验旧布尔与字符串名单（布尔须是数值型 NBT，
 *       名单须全是可解析为 UUID 的字符串），全部合法才一次性迁移；任一非法则
 *       保留旧键、不部分写入，下次登录重试。</li>
 * </ul>
 * 旧键只在「迁移成功」或「有效新组明确替代旧组」后删除对应两项；崩溃窗口说明同旧版
 * （SavedData 与 player.dat 保存时机不同步，窄窗口可接受）。迁移只处理自话/互聊两组
 * （睡觉时安静为新增功能，无旧数据源，一律走缺省）。
 */
public final class SelfTalkMigration {

    /** 旧版（1.1.1 及更早）persistentData 中的设置 key（仅迁移读删用；旧类 PlayerSettingsStorage 已随迁移删除） */
    private static final String OLD_ENABLED = "maid_self_talk:enabled";
    private static final String OLD_DISABLED_MAIDS = "maid_self_talk:disabled_maids";
    private static final String OLD_INTER_CHAT_ENABLED = "maid_self_talk:inter_chat_enabled";
    private static final String OLD_INTER_CHAT_DISABLED_MAIDS = "maid_self_talk:inter_chat_disabled_maids";

    private SelfTalkMigration() {
    }

    /** 玩家登录时调用（服务端主线程）；实际迁移经 PlayerSettingsStore 委托 {@link #migrateSettings} */
    public static void migrate(ServerPlayer player) {
        CompoundTag sub = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        PlayerSettingsStore.migrateFromLegacy(player.server, player.getUUID(), sub);
    }

    /**
     * 实际迁移逻辑（包内可见供自检）：data 为目标 SavedData，sub 为旧 PlayerPersisted 子标签。
     * 自检经包内 {@code PlayerSettingsSavedData.load} 构造 data 后直接调用本方法。
     */
    static void migrateSettings(PlayerSettingsSavedData data, UUID playerUuid, CompoundTag sub) {
        // ==== 自话 ====
        if (data.hasCorruptedSelfTalkMaidList(playerUuid)) {
            // 目标组名单损坏：整组保留旧键，不迁移不删除（不部分写入），等显式修复后重试
        } else if (data.hasSelfTalkData(playerUuid)) {
            // 目标组已有有效数据（含显式接管标记）：保持「新组优先」，旧键仅作残留清洗。
            // 接管标记须在旧键清洗后解除——否则显式清空到空名单的玩家会在下次登录被旧名单灌回。
            clearOldKeys(sub, OLD_ENABLED, OLD_DISABLED_MAIDS);
            data.clearSelfTalkMaidListTakenOver(playerUuid);
        } else {
            LegacyValues old = readLegacyValues(sub, OLD_ENABLED, OLD_DISABLED_MAIDS);
            if (old != null) {
                if (old.enabled() != null) {
                    data.setSelfTalkEnabled(playerUuid, old.enabled());
                }
                if (!old.disabledMaids().isEmpty()) {
                    mergeMaidIds(data.getSelfTalkMaidOverrides(), playerUuid, old.disabledMaids(), data);
                }
                clearOldKeys(sub, OLD_ENABLED, OLD_DISABLED_MAIDS);
            }
            // 旧值非法（null）：保留旧键、不部分写入，下次登录重试
        }

        // ==== 互聊 ====
        if (data.hasCorruptedInterChatMaidList(playerUuid)) {
            // 目标组名单损坏：整组保留旧键，不迁移不删除
        } else if (data.hasInterChatData(playerUuid)) {
            clearOldKeys(sub, OLD_INTER_CHAT_ENABLED, OLD_INTER_CHAT_DISABLED_MAIDS);
            data.clearInterChatMaidListTakenOver(playerUuid);
        } else {
            LegacyValues old = readLegacyValues(sub, OLD_INTER_CHAT_ENABLED, OLD_INTER_CHAT_DISABLED_MAIDS);
            if (old != null) {
                if (old.enabled() != null) {
                    data.setInterChatEnabled(playerUuid, old.enabled());
                }
                if (!old.disabledMaids().isEmpty()) {
                    mergeMaidIds(data.getInterChatMaidOverrides(), playerUuid, old.disabledMaids(), data);
                }
                clearOldKeys(sub, OLD_INTER_CHAT_ENABLED, OLD_INTER_CHAT_DISABLED_MAIDS);
            }
        }
    }

    /**
     * 读取并完整校验单组旧值：布尔须是数值型 NBT（字符串/列表等一律非法）、
     * 名单须是字符串列表且每个元素可解析为 UUID。任一非法返回 null（调用方保留旧键）；
     * 键不存在视为该值为空（合法，只迁移另一项）。
     */
    private static LegacyValues readLegacyValues(CompoundTag sub, String enabledKey, String listKey) {
        Boolean enabled = null;
        if (sub.contains(enabledKey)) {
            if (!(sub.get(enabledKey) instanceof NumericTag)) {
                return null;
            }
            enabled = sub.getBoolean(enabledKey);
        }
        List<String> maids = new ArrayList<>();
        if (sub.contains(listKey)) {
            if (!(sub.get(listKey) instanceof ListTag list)) {
                return null;
            }
            for (Tag item : list) {
                if (!(item instanceof StringTag stringItem)) {
                    return null;
                }
                String value = stringItem.getAsString();
                try {
                    UUID.fromString(value);
                } catch (IllegalArgumentException e) {
                    return null;
                }
                maids.add(value);
            }
        }
        return new LegacyValues(enabled, maids);
    }

    /** 单组旧值：enabled 为 null 表示旧布尔键不存在（只迁移名单） */
    private record LegacyValues(Boolean enabled, List<String> disabledMaids) {
    }

    /** 旧名单并入 SavedData 名单表（覆盖同名条目），合并后标记存档写入 */
    private static void mergeMaidIds(Map<String, Map<String, Boolean>> table, UUID playerUuid,
                                     List<String> maidIds, PlayerSettingsSavedData data) {
        String playerKey = playerUuid.toString();
        Map<String, Boolean> inner = new HashMap<>(table.getOrDefault(playerKey, Map.of()));
        for (String id : maidIds) {
            inner.put(id, false);
        }
        table.put(playerKey, inner);
        data.markDirty();
    }

    /** 清除单组的两项旧 key（仅在迁移成功或有效新组替代后调用；键不存在时为无操作） */
    private static void clearOldKeys(CompoundTag sub, String enabledKey, String listKey) {
        sub.remove(enabledKey);
        sub.remove(listKey);
    }
}
