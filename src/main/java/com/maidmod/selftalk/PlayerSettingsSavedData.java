package com.maidmod.selftalk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家设置的世界存档载体（1.1.2 起，Forge 1.20.1 无附件系统，用标准 SavedData）。
 * <p>
 * 挂 overworld 的 {@code getDataStorage()}（{@link #get} 固定从 {@code server.overworld()} 取，
 * 任何维度的女仆 tick 都读同一份数据）；数据最终写入该世界 level.dat 的命名条目。
 * 玩家"从未设置过"时不出现条目（只存非默认项，与旧 persistentData 名单"只存关闭项"哲学一致）。
 * <p>
 * 所有方法均要求在服务端主线程调用（状态机/网络 handler 均满足）。
 */
public final class PlayerSettingsSavedData extends SavedData {

    private static final String NAME = "maid_self_talk_player_settings";

    // ===== 数据表：玩家 UUID 字符串 -> 值 =====

    /** 自话全局开关（缺失 = true 启用） */
    private final Map<String, Boolean> selfTalkEnabled = new HashMap<>();
    /** 自话单只关闭名单：玩家 UUID -> 女仆 UUID（值恒 false 关闭项） */
    private final Map<String, Map<String, Boolean>> selfTalkMaidOverrides = new HashMap<>();
    /** 互聊全局开关（缺失 = true 启用） */
    private final Map<String, Boolean> interChatEnabled = new HashMap<>();
    /** 互聊单只关闭名单（值恒 false） */
    private final Map<String, Map<String, Boolean>> interChatMaidOverrides = new HashMap<>();
    /** 睡觉时安静全局开关（缺失 = true 安静；极性与上相反） */
    private final Map<String, Boolean> sleepQuietEnabled = new HashMap<>();
    /** 睡觉时安静单只名单（值恒 true 安静项，仅全局关闭时生效） */
    private final Map<String, Map<String, Boolean>> sleepQuietMaidOverrides = new HashMap<>();
    /** 自定义 Prompt 全局段：玩家 UUID -> Prompt 文本（空串条目不落盘） */
    private final Map<String, String> customPromptGlobal = new HashMap<>();
    /** 自定义 Prompt 单只段：玩家 UUID -> 女仆 UUID -> Prompt 文本（空串条目不落盘） */
    private final Map<String, Map<String, String>> customPromptMaid = new HashMap<>();
    /** 自定义 Prompt「全局覆盖单只」开关（仅存 true 项，缺失 = 关闭） */
    private final Map<String, Boolean> customPromptOverride = new HashMap<>();
    /** Tool 调用玩家全局开关（仅存 true 项，缺失 = 关闭——Tool 玩家默认关闭） */
    private final Map<String, Boolean> toolCallEnabled = new HashMap<>();
    /** Tool 调用单只关闭名单（值恒 false 关闭项，缺失 = 跟随全局） */
    private final Map<String, Map<String, Boolean>> toolCallMaidOverrides = new HashMap<>();

    private PlayerSettingsSavedData() {
    }

    /** 固定从 overworld 取（每个维度有独立 DataStorage，只有 overworld 是永驻单实例） */
    public static PlayerSettingsSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PlayerSettingsSavedData::load, PlayerSettingsSavedData::new, NAME);
    }

    private static PlayerSettingsSavedData load(CompoundTag tag) {
        PlayerSettingsSavedData data = new PlayerSettingsSavedData();
        readGlobal(tag, "selfTalkEnabled", data.selfTalkEnabled);
        readMaidList(tag, "selfTalkMaidOverrides", data.selfTalkMaidOverrides);
        readGlobal(tag, "interChatEnabled", data.interChatEnabled);
        readMaidList(tag, "interChatMaidOverrides", data.interChatMaidOverrides);
        readGlobal(tag, "sleepQuietEnabled", data.sleepQuietEnabled);
        readMaidList(tag, "sleepQuietMaidOverrides", data.sleepQuietMaidOverrides);
        readStringGlobal(tag, "customPromptGlobal", data.customPromptGlobal);
        readMaidStringList(tag, "customPromptMaid", data.customPromptMaid);
        readGlobal(tag, "customPromptOverride", data.customPromptOverride);
        readGlobal(tag, "toolCallEnabled", data.toolCallEnabled);
        readMaidList(tag, "toolCallMaidOverrides", data.toolCallMaidOverrides);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        writeGlobal(tag, "selfTalkEnabled", selfTalkEnabled);
        writeMaidList(tag, "selfTalkMaidOverrides", selfTalkMaidOverrides);
        writeGlobal(tag, "interChatEnabled", interChatEnabled);
        writeMaidList(tag, "interChatMaidOverrides", interChatMaidOverrides);
        writeGlobal(tag, "sleepQuietEnabled", sleepQuietEnabled);
        writeMaidList(tag, "sleepQuietMaidOverrides", sleepQuietMaidOverrides);
        writeStringGlobal(tag, "customPromptGlobal", customPromptGlobal);
        writeMaidStringList(tag, "customPromptMaid", customPromptMaid);
        writeGlobal(tag, "customPromptOverride", customPromptOverride);
        writeGlobal(tag, "toolCallEnabled", toolCallEnabled);
        writeMaidList(tag, "toolCallMaidOverrides", toolCallMaidOverrides);
        return tag;
    }

    // ===== 全局读写 =====

    public boolean getSelfTalkEnabled(UUID playerUuid) {
        return selfTalkEnabled.getOrDefault(playerUuid.toString(), true);
    }

    public void setSelfTalkEnabled(UUID playerUuid, boolean enabled) {
        setGlobalBool(selfTalkEnabled, playerUuid, enabled, true);
    }

    public boolean getInterChatEnabled(UUID playerUuid) {
        return interChatEnabled.getOrDefault(playerUuid.toString(), true);
    }

    public void setInterChatEnabled(UUID playerUuid, boolean enabled) {
        setGlobalBool(interChatEnabled, playerUuid, enabled, true);
    }

    public boolean getSleepQuietGlobal(UUID playerUuid) {
        return sleepQuietEnabled.getOrDefault(playerUuid.toString(), true);
    }

    public void setSleepQuietGlobal(UUID playerUuid, boolean quiet) {
        setGlobalBool(sleepQuietEnabled, playerUuid, quiet, true);
    }

    // ===== 名单读写 =====

    /** 自话/互聊关闭项（值恒 false）：名单包含该女仆 = 已关闭 */
    public boolean isSelfTalkMaidDisabled(UUID playerUuid, UUID maidUuid) {
        return isMaidListed(selfTalkMaidOverrides, playerUuid, maidUuid);
    }

    public void setSelfTalkMaidDisabled(UUID playerUuid, UUID maidUuid, boolean disabled) {
        setMaidItem(selfTalkMaidOverrides, playerUuid, maidUuid, disabled, false);
    }

    public boolean isInterChatMaidDisabled(UUID playerUuid, UUID maidUuid) {
        return isMaidListed(interChatMaidOverrides, playerUuid, maidUuid);
    }

    public void setInterChatMaidDisabled(UUID playerUuid, UUID maidUuid, boolean disabled) {
        setMaidItem(interChatMaidOverrides, playerUuid, maidUuid, disabled, false);
    }

    /** 睡觉安静项（值恒 true）：名单包含该女仆 = 这只女仆睡觉时安静 */
    public boolean isSleepQuietMaid(UUID playerUuid, UUID maidUuid) {
        Map<String, Boolean> list = sleepQuietMaidOverrides.get(playerUuid.toString());
        return list != null && list.getOrDefault(maidUuid.toString(), false);
    }

    public void setSleepQuietMaid(UUID playerUuid, UUID maidUuid, boolean quiet) {
        setMaidItem(sleepQuietMaidOverrides, playerUuid, maidUuid, quiet, true);
    }

    // ===== 自定义 Prompt =====

    /** 玩家自定义 Prompt 全局段（缺失 = 空串，未填写） */
    public String getCustomPromptGlobal(UUID playerUuid) {
        return customPromptGlobal.getOrDefault(playerUuid.toString(), "");
    }

    /** 玩家自定义 Prompt 全局段：空白时移除键（回到缺省），非空才落盘 */
    public void setCustomPromptGlobal(UUID playerUuid, String prompt) {
        String key = playerUuid.toString();
        if (prompt == null || prompt.isBlank()) {
            customPromptGlobal.remove(key);
        } else {
            customPromptGlobal.put(key, prompt);
        }
        setDirty();
    }

    /** 单只自定义 Prompt（缺失 = 空串，未填写） */
    public String getCustomPromptForMaid(UUID playerUuid, UUID maidUuid) {
        Map<String, String> list = customPromptMaid.get(playerUuid.toString());
        return list == null ? "" : list.getOrDefault(maidUuid.toString(), "");
    }

    /** 单只自定义 Prompt：空白时移除条目（内层空则移除外层键），非空才落盘 */
    public void setCustomPromptForMaid(UUID playerUuid, UUID maidUuid, String prompt) {
        String playerKey = playerUuid.toString();
        String maidKey = maidUuid.toString();
        Map<String, String> list = customPromptMaid.get(playerKey);
        if (list == null) {
            if (prompt == null || prompt.isBlank()) {
                return;
            }
            list = new HashMap<>();
        }
        if (prompt == null || prompt.isBlank()) {
            list.remove(maidKey);
        } else {
            list.put(maidKey, prompt);
        }
        // 内层空则移除外层键，避免残留空壳
        if (list.isEmpty()) {
            customPromptMaid.remove(playerKey);
        } else {
            customPromptMaid.put(playerKey, list);
        }
        setDirty();
    }

    /** 「全局配置覆盖单只」开关（缺失 = false，两段都注入） */
    public boolean isCustomPromptOverrideEnabled(UUID playerUuid) {
        return customPromptOverride.getOrDefault(playerUuid.toString(), false);
    }

    /** 「全局配置覆盖单只」开关：开启存 true，关闭移除键（仅存非默认项） */
    public void setCustomPromptOverride(UUID playerUuid, boolean enabled) {
        setGlobalBool(customPromptOverride, playerUuid, enabled, false);
    }

    // ===== Tool 调用 =====

    /** 玩家 Tool 全局开关（缺失 = false——Tool 玩家默认关闭，与自话/互聊极性相反） */
    public boolean isToolCallGlobal(UUID playerUuid) {
        return toolCallEnabled.getOrDefault(playerUuid.toString(), false);
    }

    /** 玩家 Tool 全局开关：开启存 true，关闭移除键（仅存非默认项） */
    public void setToolCallGlobal(UUID playerUuid, boolean enabled) {
        setGlobalBool(toolCallEnabled, playerUuid, enabled, false);
    }

    /** 单只 Tool 关闭名单是否包含该女仆（缺失 = 未关闭，跟随全局） */
    public boolean isToolCallMaidDisabled(UUID playerUuid, UUID maidUuid) {
        return isMaidListed(toolCallMaidOverrides, playerUuid, maidUuid);
    }

    /** 单只 Tool 关闭名单：关闭时存 false，恢复时移除 */
    public void setToolCallMaidDisabled(UUID playerUuid, UUID maidUuid, boolean disabled) {
        setMaidItem(toolCallMaidOverrides, playerUuid, maidUuid, disabled, false);
    }

    /** 某玩家自定义 Prompt 单只条数（容量上限检查用） */
    public int countCustomPromptMaidEntries(UUID playerUuid) {
        Map<String, String> list = customPromptMaid.get(playerUuid.toString());
        return list == null ? 0 : list.size();
    }

    /** 某玩家 Tool 单只关闭名单条数（容量上限检查用） */
    public int countToolCallMaidOverrides(UUID playerUuid) {
        Map<String, Boolean> list = toolCallMaidOverrides.get(playerUuid.toString());
        return list == null ? 0 : list.size();
    }

    public int countMaidOverrides(Map<String, Map<String, Boolean>> table, UUID playerUuid) {
        Map<String, Boolean> list = table.get(playerUuid.toString());
        return list == null ? 0 : list.size();
    }

    /** 某玩家自话单只名单条数（容量上限检查用） */
    public int countSelfTalkMaidOverrides(UUID playerUuid) {
        return countMaidOverrides(selfTalkMaidOverrides, playerUuid);
    }

    /** 某玩家互聊单只名单条数（容量上限检查用） */
    public int countInterChatMaidOverrides(UUID playerUuid) {
        return countMaidOverrides(interChatMaidOverrides, playerUuid);
    }

    /** 某玩家睡觉安静单只名单条数（容量上限检查用） */
    public int countSleepQuietMaidOverrides(UUID playerUuid) {
        return countMaidOverrides(sleepQuietMaidOverrides, playerUuid);
    }

    /** 名单表访问器（供容量检查与迁移合并使用；调用方不得修改返回的引用，先改后调 markDirty） */
    public Map<String, Map<String, Boolean>> getSelfTalkMaidOverrides() {
        return selfTalkMaidOverrides;
    }

    public Map<String, Map<String, Boolean>> getInterChatMaidOverrides() {
        return interChatMaidOverrides;
    }

    public Map<String, Map<String, Boolean>> getSleepQuietMaidOverrides() {
        return sleepQuietMaidOverrides;
    }

    /** 迁移合并等绕过 setter 的直改路径结束后调用，通知存档写入 */
    public void markDirty() {
        setDirty();
    }

    /** 自话组是否已有该玩家条目（迁移幂等判定：已迁移则以 SavedData 为准） */
    public boolean hasSelfTalkData(UUID playerUuid) {
        String key = playerUuid.toString();
        return selfTalkEnabled.containsKey(key) || selfTalkMaidOverrides.containsKey(key);
    }

    /** 互聊组是否已有该玩家条目（迁移幂等判定） */
    public boolean hasInterChatData(UUID playerUuid) {
        String key = playerUuid.toString();
        return interChatEnabled.containsKey(key) || interChatMaidOverrides.containsKey(key);
    }

    // ===== 共用 =====

    private void setGlobalBool(Map<String, Boolean> map, UUID playerUuid, boolean value, boolean defaultValue) {
        String key = playerUuid.toString();
        if (value == defaultValue) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
        setDirty();
    }

    private static boolean isMaidListed(Map<String, Map<String, Boolean>> table, UUID playerUuid, UUID maidUuid) {
        Map<String, Boolean> list = table.get(playerUuid.toString());
        return list != null && list.containsKey(maidUuid.toString());
    }

    private void setMaidItem(Map<String, Map<String, Boolean>> table, UUID playerUuid, UUID maidUuid,
                             boolean setItem, boolean itemValue) {
        String playerKey = playerUuid.toString();
        Map<String, Boolean> list = table.get(playerKey);
        if (list == null) {
            list = new HashMap<>();
        }
        String maidKey = maidUuid.toString();
        if (setItem) {
            list.put(maidKey, itemValue);
        } else {
            list.remove(maidKey);
        }
        if (list.isEmpty()) {
            table.remove(playerKey);
        } else {
            table.put(playerKey, list);
        }
        setDirty();
    }

    /** 全局表序列化：玩家 UUID 字符串为键（NBT 允许任意字符串键） */
    private static void writeGlobal(CompoundTag tag, String key, Map<String, Boolean> map) {
        CompoundTag sub = new CompoundTag();
        for (Map.Entry<String, Boolean> e : map.entrySet()) {
            sub.putBoolean(e.getKey(), e.getValue());
        }
        tag.put(key, sub);
    }

    private static void readGlobal(CompoundTag tag, String key, Map<String, Boolean> target) {
        CompoundTag sub = tag.getCompound(key);
        for (String k : sub.getAllKeys()) {
            target.put(k, sub.getBoolean(k));
        }
    }

    /** 名单序列化：玩家 UUID -> 女仆 UUID 列表（条目值由 itemValue 语义决定，直接存字符串表） */
    private static void writeMaidList(CompoundTag tag, String key, Map<String, Map<String, Boolean>> table) {
        CompoundTag sub = new CompoundTag();
        for (Map.Entry<String, Map<String, Boolean>> e : table.entrySet()) {
            ListTag list = new ListTag();
            for (Map.Entry<String, Boolean> item : e.getValue().entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("u", item.getKey());
                entry.putBoolean("v", item.getValue());
                list.add(entry);
            }
            sub.put(e.getKey(), list);
        }
        tag.put(key, sub);
    }

    private static void readMaidList(CompoundTag tag, String key, Map<String, Map<String, Boolean>> target) {
        CompoundTag sub = tag.getCompound(key);
        for (String playerKey : sub.getAllKeys()) {
            ListTag list = sub.getList(playerKey, Tag.TAG_COMPOUND);
            Map<String, Boolean> inner = new HashMap<>();
            for (Tag t : list) {
                CompoundTag entry = (CompoundTag) t;
                inner.put(entry.getString("u"), entry.getBoolean("v"));
            }
            target.put(playerKey, inner);
        }
    }

    /** 全局字符串表序列化（自定义 Prompt 全局段） */
    private static void writeStringGlobal(CompoundTag tag, String key, Map<String, String> map) {
        CompoundTag sub = new CompoundTag();
        for (Map.Entry<String, String> e : map.entrySet()) {
            sub.putString(e.getKey(), e.getValue());
        }
        tag.put(key, sub);
    }

    private static void readStringGlobal(CompoundTag tag, String key, Map<String, String> target) {
        CompoundTag sub = tag.getCompound(key);
        for (String k : sub.getAllKeys()) {
            target.put(k, sub.getString(k));
        }
    }

    /** 两级字符串表序列化（自定义 Prompt 单只段）：玩家 UUID -> 女仆 UUID -> Prompt 文本 */
    private static void writeMaidStringList(CompoundTag tag, String key, Map<String, Map<String, String>> table) {
        CompoundTag sub = new CompoundTag();
        for (Map.Entry<String, Map<String, String>> e : table.entrySet()) {
            ListTag list = new ListTag();
            for (Map.Entry<String, String> item : e.getValue().entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("u", item.getKey());
                entry.putString("v", item.getValue());
                list.add(entry);
            }
            sub.put(e.getKey(), list);
        }
        tag.put(key, sub);
    }

    private static void readMaidStringList(CompoundTag tag, String key, Map<String, Map<String, String>> target) {
        CompoundTag sub = tag.getCompound(key);
        for (String playerKey : sub.getAllKeys()) {
            ListTag list = sub.getList(playerKey, Tag.TAG_COMPOUND);
            Map<String, String> inner = new HashMap<>();
            for (Tag t : list) {
                CompoundTag entry = (CompoundTag) t;
                inner.put(entry.getString("u"), entry.getString("v"));
            }
            target.put(playerKey, inner);
        }
    }
}
