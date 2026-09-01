package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;

/**
 * 玩家设置统一读写入口（1.1.2 起：宿主从玩家 persistentData 迁移到 overworld SavedData）。
 * <p>
 * 所有方法均要求在<b>服务端主线程</b>调用，内部一律经 {@code server.overworld()} 取
 * {@link PlayerSettingsSavedData}——任何维度的女仆 tick 都读同一份数据，随世界存档持久化。
 * <p>
 * 缺省语义（与 neo 线一致）：
 * <ul>
 *   <li>自话/互聊全局开关缺省 true（触发），玩家关闭时存 false；</li>
 *   <li>睡觉时安静全局开关缺省 true（安静）——<b>极性与上相反</b>，玩家允许说话时存 false；</li>
 *   <li>自话/互聊单只名单只存 false（关闭项）；睡觉时安静单只名单只存 true（安静项）；</li>
 *   <li>自定义 Prompt 全局/单只段空串不落盘（缺失 = 未填写）；覆盖开关与 Tool 全局开关仅存 true；</li>
 *   <li>Tool 单只名单只存 false（关闭项），Tool 全局缺省 false——玩家默认不开启 Tool。</li>
 * </ul>
 */
public final class PlayerSettingsStore {

    private PlayerSettingsStore() {
    }

    private static PlayerSettingsSavedData data(MinecraftServer server) {
        return PlayerSettingsSavedData.get(server);
    }

    // ===== 自话 =====

    /** 玩家自话全局开关（缺省 = 启用） */
    public static boolean isSelfTalkEnabled(MinecraftServer server, UUID playerUuid) {
        return data(server).getSelfTalkEnabled(playerUuid);
    }

    /** 玩家自话全局开关：启用时移除键（回到缺省），关闭时存 false */
    public static void setSelfTalkEnabled(MinecraftServer server, UUID playerUuid, boolean enabled) {
        data(server).setSelfTalkEnabled(playerUuid, enabled);
    }

    /** 单只关闭名单是否包含该女仆（缺省 = 未关闭） */
    public static boolean isSelfTalkMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return data(server).isSelfTalkMaidDisabled(playerUuid, maidUuid);
    }

    /** 单只关闭名单：关闭时存 false，恢复时移除 */
    public static void setSelfTalkMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid, boolean disabled) {
        data(server).setSelfTalkMaidDisabled(playerUuid, maidUuid, disabled);
    }

    /** 女仆自话有效值：全局开关 && 单只名单不含该女仆（无主女仆按启用处理） */
    public static boolean isSelfTalkEnabledForMaid(MinecraftServer server, EntityMaid maid) {
        UUID ownerUuid = maid.getOwnerUUID();
        if (ownerUuid == null) {
            return true;
        }
        return isSelfTalkEnabled(server, ownerUuid) && !isSelfTalkMaidDisabled(server, ownerUuid, maid.getUUID());
    }

    // ===== 互聊 =====

    /** 玩家互聊全局开关（缺省 = 启用） */
    public static boolean isInterChatEnabled(MinecraftServer server, UUID playerUuid) {
        return data(server).getInterChatEnabled(playerUuid);
    }

    /** 玩家互聊全局开关：启用时移除键（回到缺省），关闭时存 false */
    public static void setInterChatEnabled(MinecraftServer server, UUID playerUuid, boolean enabled) {
        data(server).setInterChatEnabled(playerUuid, enabled);
    }

    /** 单只关闭名单是否包含该女仆（缺省 = 未关闭） */
    public static boolean isInterChatMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return data(server).isInterChatMaidDisabled(playerUuid, maidUuid);
    }

    /** 单只关闭名单：关闭时存 false，恢复时移除 */
    public static void setInterChatMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid, boolean disabled) {
        data(server).setInterChatMaidDisabled(playerUuid, maidUuid, disabled);
    }

    /** 女仆互聊有效值：全局开关 && 单只名单不含该女仆（无主女仆按启用处理） */
    public static boolean isInterChatEnabledForMaid(MinecraftServer server, EntityMaid maid) {
        UUID ownerUuid = maid.getOwnerUUID();
        if (ownerUuid == null) {
            return true;
        }
        return isInterChatEnabled(server, ownerUuid) && !isInterChatMaidDisabled(server, ownerUuid, maid.getUUID());
    }

    // ===== 睡觉时安静 =====

    /** 玩家「睡觉时安静」全局开关（缺省 = true 安静） */
    public static boolean isSleepQuietGlobal(MinecraftServer server, UUID playerUuid) {
        return data(server).getSleepQuietGlobal(playerUuid);
    }

    /** 玩家「睡觉时安静」全局开关：安静时移除键（回到缺省），允许说话时存 false */
    public static void setSleepQuietGlobal(MinecraftServer server, UUID playerUuid, boolean quiet) {
        data(server).setSleepQuietGlobal(playerUuid, quiet);
    }

    /** 单只安静名单是否包含该女仆（缺省 = 未单独开启安静） */
    public static boolean isSleepQuietMaid(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return data(server).isSleepQuietMaid(playerUuid, maidUuid);
    }

    /** 单只安静名单：开启安静时存 true，恢复说话时移除 */
    public static void setSleepQuietMaid(MinecraftServer server, UUID playerUuid, UUID maidUuid, boolean quiet) {
        data(server).setSleepQuietMaid(playerUuid, maidUuid, quiet);
    }

    /**
     * 女仆「睡觉时安静」有效值（睡眠 gate 统一判定口）。
     * <p>
     * 布尔表（注意极性与自话/互聊相反）：
     * <ol>
     *   <li>无主女仆 → false（不受玩家设置约束，维持现状）；</li>
     *   <li>全局安静（缺省 true）→ true；单只名单此时不可配置、内容被忽略；</li>
     *   <li>全局允许说话（false）→ 单只名单包含该女仆（true 项）则 true，否则 false。</li>
     * </ol>
     */
    public static boolean isSleepQuietForMaid(MinecraftServer server, EntityMaid maid) {
        UUID ownerUuid = maid.getOwnerUUID();
        if (ownerUuid == null) {
            return false;
        }
        if (isSleepQuietGlobal(server, ownerUuid)) {
            return true;
        }
        return isSleepQuietMaid(server, ownerUuid, maid.getUUID());
    }

    // ===== 自定义 Prompt =====

    /** 玩家自定义 Prompt 全局段（缺省 = 空串，未填写） */
    public static String getCustomPromptGlobal(MinecraftServer server, UUID playerUuid) {
        return data(server).getCustomPromptGlobal(playerUuid);
    }

    /** 玩家自定义 Prompt 全局段：空白时移除键（回到缺省），非空才落盘 */
    public static void setCustomPromptGlobal(MinecraftServer server, UUID playerUuid, String prompt) {
        data(server).setCustomPromptGlobal(playerUuid, prompt);
    }

    /** 单只自定义 Prompt（缺省 = 空串，未填写） */
    public static String getCustomPromptForMaid(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return data(server).getCustomPromptForMaid(playerUuid, maidUuid);
    }

    /** 单只自定义 Prompt：空白时移除条目（内层空则移除外层键），非空才落盘 */
    public static void setCustomPromptForMaid(MinecraftServer server, UUID playerUuid, UUID maidUuid, String prompt) {
        data(server).setCustomPromptForMaid(playerUuid, maidUuid, prompt);
    }

    /** 「全局配置覆盖单只」开关（缺省 = false，两段都注入） */
    public static boolean isCustomPromptOverrideEnabled(MinecraftServer server, UUID playerUuid) {
        return data(server).isCustomPromptOverrideEnabled(playerUuid);
    }

    /** 「全局配置覆盖单只」开关：开启存 true，关闭移除键（仅存非默认项） */
    public static void setCustomPromptOverride(MinecraftServer server, UUID playerUuid, boolean enabled) {
        data(server).setCustomPromptOverride(playerUuid, enabled);
    }

    // ===== Tool 调用 =====

    /** 玩家 Tool 全局开关（缺省 = false——Tool 玩家默认关闭，与自话/互聊极性相反） */
    public static boolean isToolCallGlobal(MinecraftServer server, UUID playerUuid) {
        return data(server).isToolCallGlobal(playerUuid);
    }

    /** 玩家 Tool 全局开关：开启存 true，关闭移除键（仅存非默认项） */
    public static void setToolCallGlobal(MinecraftServer server, UUID playerUuid, boolean enabled) {
        data(server).setToolCallGlobal(playerUuid, enabled);
    }

    /** 单只 Tool 关闭名单是否包含该女仆（缺省 = 未关闭，跟随全局） */
    public static boolean isToolCallMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return data(server).isToolCallMaidDisabled(playerUuid, maidUuid);
    }

    /** 单只 Tool 关闭名单：关闭时存 false，恢复时移除 */
    public static void setToolCallMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid, boolean disabled) {
        data(server).setToolCallMaidDisabled(playerUuid, maidUuid, disabled);
    }

    /**
     * 女仆 Tool 调用有效值（触发侧统一判定口，须在派发时调用）。
     * <p>
     * 有效 = 管理员 Tool 总闸 && 管理员玩家配置开关 && 玩家全局开关（缺省 false）&& 单只未被关闭。
     * 注意极性：管理员关闭「允许玩家自定义配置」时自话/互聊<b>视为启用</b>，
     * Tool 恰恰相反<b>视为关闭</b>——Tool 玩家默认关闭，管理员禁用玩家配置时不应替玩家打开。
     * 无主女仆同样不开启。
     */
    public static boolean isToolCallEnabledForMaid(MinecraftServer server, EntityMaid maid) {
        if (!Config.TOOL_CALL_ENABLED.get() || !Config.PLAYER_OPTION_ENABLED.get()) {
            return false;
        }
        UUID ownerUuid = maid.getOwnerUUID();
        if (ownerUuid == null) {
            return false;
        }
        return isToolCallGlobal(server, ownerUuid)
                && !isToolCallMaidDisabled(server, ownerUuid, maid.getUUID());
    }

    // ===== 迁移专用 =====

    /** 该玩家自话组是否已在 SavedData 有条目（已迁移过则迁移时以 SavedData 为准） */
    public static boolean hasSelfTalkData(MinecraftServer server, UUID playerUuid) {
        return data(server).hasSelfTalkData(playerUuid);
    }

    /** 该玩家互聊组是否已在 SavedData 有条目（已迁移过则迁移时以 SavedData 为准） */
    public static boolean hasInterChatData(MinecraftServer server, UUID playerUuid) {
        return data(server).hasInterChatData(playerUuid);
    }

    public static Map<String, Map<String, Boolean>> selfTalkMaidTable(MinecraftServer server) {
        return data(server).getSelfTalkMaidOverrides();
    }

    public static Map<String, Map<String, Boolean>> interChatMaidTable(MinecraftServer server) {
        return data(server).getInterChatMaidOverrides();
    }

    /** 迁移合并后标记存档写入 */
    public static void markDirty(MinecraftServer server) {
        data(server).markDirty();
    }

    // ===== 容量检查 =====

    /** 某玩家自话单只名单条数（上限检查用） */
    public static int countSelfTalkMaidOverrides(MinecraftServer server, UUID playerUuid) {
        return data(server).countSelfTalkMaidOverrides(playerUuid);
    }

    /** 某玩家互聊单只名单条数（上限检查用） */
    public static int countInterChatMaidOverrides(MinecraftServer server, UUID playerUuid) {
        return data(server).countInterChatMaidOverrides(playerUuid);
    }

    /** 某玩家睡觉安静单只名单条数（上限检查用） */
    public static int countSleepQuietMaidOverrides(MinecraftServer server, UUID playerUuid) {
        return data(server).countSleepQuietMaidOverrides(playerUuid);
    }

    /** 某玩家自定义 Prompt 单只条数（上限检查用；与布尔名单各自计数，上限一致） */
    public static int countCustomPromptMaidEntries(MinecraftServer server, UUID playerUuid) {
        return data(server).countCustomPromptMaidEntries(playerUuid);
    }

    /** 某玩家 Tool 单只关闭名单条数（上限检查用） */
    public static int countToolCallMaidOverrides(MinecraftServer server, UUID playerUuid) {
        return data(server).countToolCallMaidOverrides(playerUuid);
    }
}
