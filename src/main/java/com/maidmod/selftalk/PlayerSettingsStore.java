package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.attachment.AttachmentType;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 玩家设置统一读写入口（1.1.2 起：宿主从玩家实体迁移到 overworld Level 附件）。
 * <p>
 * 所有方法均要求在<b>服务端主线程</b>调用（状态机 MaidTickEvent 与网络 handler 的
 * enqueueWork 均在主线程），内部一律 {@code server.overworld()} 取数——任何维度的女仆
 * tick 都读同一份数据，彼此不隔离、随世界存档持久化。
 * <p>
 * 读取路径用 getExistingData + getOrDefault 表达缺省值（不向存档安装默认壳）；
 * 写入路径拷贝后 setData 回写（与原名单"只存非默认项"哲学一致）：
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

    // ===== 自话 =====

    /** 玩家自话全局开关（缺省 = 启用） */
    public static boolean isSelfTalkEnabled(MinecraftServer server, UUID playerUuid) {
        return getMap(server, SelfTalkAttachments.LEVEL_SELF_TALK_ENABLED).getOrDefault(playerUuid.toString(), true);
    }

    /** 玩家自话全局开关：启用时移除键（回到缺省），关闭时存 false */
    public static void setSelfTalkEnabled(MinecraftServer server, UUID playerUuid, boolean enabled) {
        setGlobalBool(server, SelfTalkAttachments.LEVEL_SELF_TALK_ENABLED, playerUuid, enabled, true);
    }

    /** 单只关闭名单是否包含该女仆（缺省 = 未关闭） */
    public static boolean isSelfTalkMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return isMaidDisabled(server, SelfTalkAttachments.LEVEL_SELF_TALK_MAID_OVERRIDES, playerUuid, maidUuid);
    }

    /** 单只关闭名单：关闭时存 false，恢复时移除 */
    public static void setSelfTalkMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid, boolean disabled) {
        setMaidItem(server, SelfTalkAttachments.LEVEL_SELF_TALK_MAID_OVERRIDES, playerUuid, maidUuid, disabled, false);
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
        return getMap(server, SelfTalkAttachments.LEVEL_INTER_CHAT_ENABLED).getOrDefault(playerUuid.toString(), true);
    }

    /** 玩家互聊全局开关：启用时移除键（回到缺省），关闭时存 false */
    public static void setInterChatEnabled(MinecraftServer server, UUID playerUuid, boolean enabled) {
        setGlobalBool(server, SelfTalkAttachments.LEVEL_INTER_CHAT_ENABLED, playerUuid, enabled, true);
    }

    /** 单只关闭名单是否包含该女仆（缺省 = 未关闭） */
    public static boolean isInterChatMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return isMaidDisabled(server, SelfTalkAttachments.LEVEL_INTER_CHAT_MAID_OVERRIDES, playerUuid, maidUuid);
    }

    /** 单只关闭名单：关闭时存 false，恢复时移除 */
    public static void setInterChatMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid, boolean disabled) {
        setMaidItem(server, SelfTalkAttachments.LEVEL_INTER_CHAT_MAID_OVERRIDES, playerUuid, maidUuid, disabled, false);
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
        return getMap(server, SelfTalkAttachments.LEVEL_SLEEP_QUIET_ENABLED).getOrDefault(playerUuid.toString(), true);
    }

    /** 玩家「睡觉时安静」全局开关：安静时移除键（回到缺省），允许说话时存 false */
    public static void setSleepQuietGlobal(MinecraftServer server, UUID playerUuid, boolean quiet) {
        setGlobalBool(server, SelfTalkAttachments.LEVEL_SLEEP_QUIET_ENABLED, playerUuid, quiet, true);
    }

    /** 单只安静名单是否包含该女仆（缺省 = 未单独开启安静） */
    public static boolean isSleepQuietMaid(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return isMaidQuiet(server, SelfTalkAttachments.LEVEL_SLEEP_QUIET_MAID_OVERRIDES, playerUuid, maidUuid);
    }

    /** 单只安静名单：开启安静时存 true，恢复说话时移除 */
    public static void setSleepQuietMaid(MinecraftServer server, UUID playerUuid, UUID maidUuid, boolean quiet) {
        setMaidItem(server, SelfTalkAttachments.LEVEL_SLEEP_QUIET_MAID_OVERRIDES, playerUuid, maidUuid, quiet, true);
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
        return getStringMap(server, SelfTalkAttachments.LEVEL_CUSTOM_PROMPT_GLOBAL)
                .getOrDefault(playerUuid.toString(), StringUtils.EMPTY);
    }

    /** 玩家自定义 Prompt 全局段：空白时移除键（回到缺省），非空才落盘 */
    public static void setCustomPromptGlobal(MinecraftServer server, UUID playerUuid, String prompt) {
        Map<String, String> map = new HashMap<>(server.overworld().getData(SelfTalkAttachments.LEVEL_CUSTOM_PROMPT_GLOBAL));
        String key = playerUuid.toString();
        if (StringUtils.isBlank(prompt)) {
            map.remove(key);
        } else {
            map.put(key, prompt);
        }
        server.overworld().setData(SelfTalkAttachments.LEVEL_CUSTOM_PROMPT_GLOBAL, map);
    }

    /** 单只自定义 Prompt（缺省 = 空串，未填写） */
    public static String getCustomPromptForMaid(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return server.overworld().getExistingData(SelfTalkAttachments.LEVEL_CUSTOM_PROMPT_MAID)
                .map(m -> m.getOrDefault(playerUuid.toString(), Map.of())
                        .getOrDefault(maidUuid.toString(), StringUtils.EMPTY))
                .orElse(StringUtils.EMPTY);
    }

    /** 单只自定义 Prompt：空白时移除条目（内层空则移除外层键），非空才落盘 */
    public static void setCustomPromptForMaid(MinecraftServer server, UUID playerUuid, UUID maidUuid, String prompt) {
        Map<String, Map<String, String>> outer = new HashMap<>(
                server.overworld().getData(SelfTalkAttachments.LEVEL_CUSTOM_PROMPT_MAID));
        String playerKey = playerUuid.toString();
        Map<String, String> inner = new HashMap<>(outer.getOrDefault(playerKey, Map.of()));
        String maidKey = maidUuid.toString();
        if (StringUtils.isBlank(prompt)) {
            inner.remove(maidKey);
        } else {
            inner.put(maidKey, prompt);
        }
        // 内层空则移除外层键，避免残留空壳（与名单轻量化惯例一致）
        if (inner.isEmpty()) {
            outer.remove(playerKey);
        } else {
            outer.put(playerKey, inner);
        }
        server.overworld().setData(SelfTalkAttachments.LEVEL_CUSTOM_PROMPT_MAID, outer);
    }

    /** 「全局配置覆盖单只」开关（缺省 = false，两段都注入） */
    public static boolean isCustomPromptOverrideEnabled(MinecraftServer server, UUID playerUuid) {
        return getMap(server, SelfTalkAttachments.LEVEL_CUSTOM_PROMPT_OVERRIDE)
                .getOrDefault(playerUuid.toString(), false);
    }

    /** 「全局配置覆盖单只」开关：开启存 true，关闭移除键（仅存非默认项） */
    public static void setCustomPromptOverride(MinecraftServer server, UUID playerUuid, boolean enabled) {
        setGlobalBool(server, SelfTalkAttachments.LEVEL_CUSTOM_PROMPT_OVERRIDE, playerUuid, enabled, false);
    }

    // ===== Tool 调用 =====

    /** 玩家 Tool 全局开关（缺省 = false——Tool 玩家默认关闭，与自话/互聊极性相反） */
    public static boolean isToolCallGlobal(MinecraftServer server, UUID playerUuid) {
        return getMap(server, SelfTalkAttachments.LEVEL_TOOL_CALL_ENABLED)
                .getOrDefault(playerUuid.toString(), false);
    }

    /** 玩家 Tool 全局开关：开启存 true，关闭移除键（仅存非默认项） */
    public static void setToolCallGlobal(MinecraftServer server, UUID playerUuid, boolean enabled) {
        setGlobalBool(server, SelfTalkAttachments.LEVEL_TOOL_CALL_ENABLED, playerUuid, enabled, false);
    }

    /** 单只 Tool 关闭名单是否包含该女仆（缺省 = 未关闭，跟随全局） */
    public static boolean isToolCallMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid) {
        return isMaidDisabled(server, SelfTalkAttachments.LEVEL_TOOL_CALL_MAID_OVERRIDES, playerUuid, maidUuid);
    }

    /** 单只 Tool 关闭名单：关闭时存 false，恢复时移除 */
    public static void setToolCallMaidDisabled(MinecraftServer server, UUID playerUuid, UUID maidUuid, boolean disabled) {
        setMaidItem(server, SelfTalkAttachments.LEVEL_TOOL_CALL_MAID_OVERRIDES, playerUuid, maidUuid, disabled, false);
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

    // ===== 环境上下文三态偏好 =====

    /**
     * 该玩家保存的环境上下文覆盖表（独立快照，不泄漏存档内部的可变 Map）。
     * <p>
     * 只含与目录默认模式不同的项，因此可能少于 32 项，也可能是空表。
     * 非法 mode 与目录外 key 一律忽略（读路径不清理存档，避免读操作产生写入）。
     */
    public static Map<String, EnvironmentContextMode> getEnvironmentContextOverrides(MinecraftServer server, UUID playerUuid) {
        Map<String, EnvironmentContextMode> result = new HashMap<>();
        if (playerUuid == null) {
            return result;
        }
        Map<String, String> saved = server.overworld()
                .getExistingData(SelfTalkAttachments.LEVEL_ENVIRONMENT_CONTEXT_MODES)
                .map(m -> m.getOrDefault(playerUuid.toString(), Map.of()))
                .orElse(Map.of());
        for (Map.Entry<String, String> entry : saved.entrySet()) {
            EnvironmentContextOption option = EnvironmentContextOption.byKey(entry.getKey());
            EnvironmentContextMode mode = EnvironmentContextMode.fromId(entry.getValue());
            if (option != null && mode != null) {
                result.put(option.key(), mode);
            }
        }
        return result;
    }

    /**
     * 该玩家对某条目的偏好模式（缺省补目录默认值）。
     * <p>
     * <b>不因管理员临时禁用或提供者缺失而篡改返回值</b>：这里只表达玩家自己的选择，
     * 有效模式的门控（{@code PLAYER_OPTION_ENABLED} 时改用默认值）由
     * {@link EnvironmentContextSettings#effectiveModes} 统一计算。
     */
    public static EnvironmentContextMode getEnvironmentContextMode(MinecraftServer server, UUID playerUuid,
                                                                   EnvironmentContextOption option) {
        return getEnvironmentContextOverrides(server, playerUuid).getOrDefault(option.key(), option.defaultMode());
    }

    /**
     * 保存某条目的模式：与目录默认一致时删键，否则写覆盖；内层表清空后删外层键。
     * <p>
     * 注意不能把「所有 random 都当默认」——原固定项改为 random 时必须落盘。
     * 值未变化时直接返回，不产生一次无意义的存档写入（同值 Set 在协议层仍会回权威快照）。
     */
    public static void setEnvironmentContextMode(MinecraftServer server, UUID playerUuid,
                                                 EnvironmentContextOption option, EnvironmentContextMode mode) {
        Map<String, Map<String, String>> outer = new HashMap<>(
                server.overworld().getData(SelfTalkAttachments.LEVEL_ENVIRONMENT_CONTEXT_MODES));
        String playerKey = playerUuid.toString();
        Map<String, String> inner = new HashMap<>(outer.getOrDefault(playerKey, Map.of()));
        String current = inner.get(option.key());
        String target = option.isOverride(mode) ? mode.id() : null;
        if (current == null ? target == null : current.equals(target)) {
            return;
        }
        if (target == null) {
            inner.remove(option.key());
        } else {
            inner.put(option.key(), target);
        }
        if (inner.isEmpty()) {
            outer.remove(playerKey);
        } else {
            outer.put(playerKey, inner);
        }
        server.overworld().setData(SelfTalkAttachments.LEVEL_ENVIRONMENT_CONTEXT_MODES, outer);
    }

    // ===== 环境信息自然语言化（实验性） =====

    /**
     * 该玩家「环境信息自然语言化」的<b>已存偏好</b>（缺省 false = 关闭，实验性功能默认关闭）。
     * <p>
     * <b>不</b>在此处叠加管理员门控：本方法只表达玩家自己的选择，
     * 有效值由 {@link #isEnvironmentContextNaturalLanguageEnabledForMaid} 统一计算
     * （管理员关闸时有效值为关闭，但玩家原来的 true 必须保留，恢复后继续生效）。
     */
    public static boolean isEnvironmentContextNaturalLanguageEnabled(MinecraftServer server, UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        return getMap(server, SelfTalkAttachments.LEVEL_ENVIRONMENT_CONTEXT_NATURAL_LANGUAGE)
                .getOrDefault(playerUuid.toString(), false);
    }

    /**
     * 保存玩家的自然语言化开关：开启存 true，关闭移除键（仅存非默认项，不留无意义的 false 覆盖）。
     * <p>
     * 同值写入直接返回，不产生一次无意义的存档写入。
     */
    public static void setEnvironmentContextNaturalLanguageEnabled(MinecraftServer server, UUID playerUuid,
                                                                   boolean enabled) {
        Map<String, Boolean> map = new HashMap<>(
                server.overworld().getData(SelfTalkAttachments.LEVEL_ENVIRONMENT_CONTEXT_NATURAL_LANGUAGE));
        String key = playerUuid.toString();
        if (map.getOrDefault(key, false) == enabled) {
            return;
        }
        if (enabled) {
            map.put(key, true);
        } else {
            map.remove(key);
        }
        server.overworld().setData(SelfTalkAttachments.LEVEL_ENVIRONMENT_CONTEXT_NATURAL_LANGUAGE, map);
    }

    /**
     * 女仆的环境信息自然语言化有效值（派发侧统一判定口，须在<b>实际派发时</b>调用）。
     * <p>
     * 有效 = 管理员允许玩家配置 && 女仆有主人 UUID && 该主人保存的开关为 true。
     * <ul>
     *   <li>主人离线同样按 UUID 查世界存档，设置继续生效；</li>
     *   <li>无主女仆用原模式（没有玩家偏好可查）；</li>
     *   <li>管理员关闭玩家配置时有效值为关闭，但<b>不</b>删除玩家原来的 true。</li>
     * </ul>
     */
    public static boolean isEnvironmentContextNaturalLanguageEnabledForMaid(MinecraftServer server, EntityMaid maid) {
        if (!Config.PLAYER_OPTION_ENABLED.get()) {
            return false;
        }
        UUID ownerUuid = maid.getOwnerUUID();
        if (ownerUuid == null) {
            return false;
        }
        return isEnvironmentContextNaturalLanguageEnabled(server, ownerUuid);
    }

    // ===== 共用读写 =====

    /** 全局布尔读写（缺省值 defaultEnabled 时移除键，值不等于缺省才落盘） */
    private static void setGlobalBool(MinecraftServer server,
                                      Supplier<AttachmentType<Map<String, Boolean>>> type,
                                      UUID playerUuid, boolean value, boolean defaultValue) {
        Map<String, Boolean> map = new HashMap<>(server.overworld().getData(type));
        String key = playerUuid.toString();
        if (value == defaultValue) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
        server.overworld().setData(type, map);
    }

    /** 单只名单是否包含该女仆（名单条目值恒等于 itemValue；读路径不安装默认壳） */
    private static boolean isMaidDisabled(MinecraftServer server,
                                          Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> type,
                                          UUID playerUuid, UUID maidUuid) {
        return server.overworld().getExistingData(type)
                .map(m -> m.getOrDefault(playerUuid.toString(), Map.of()).containsKey(maidUuid.toString()))
                .orElse(false);
    }

    /** 单只安静名单读（值恒 true 条目） */
    private static boolean isMaidQuiet(MinecraftServer server,
                                       Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> type,
                                       UUID playerUuid, UUID maidUuid) {
        return server.overworld().getExistingData(type)
                .map(m -> m.getOrDefault(playerUuid.toString(), Map.of()).getOrDefault(maidUuid.toString(), false))
                .orElse(false);
    }

    /** 单只名单写：itemValue 为真存条目、为假移除条目（名单只存非默认态，不膨胀） */
    private static void setMaidItem(MinecraftServer server,
                                    Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> type,
                                    UUID playerUuid, UUID maidUuid, boolean setItem, boolean itemValue) {
        Map<String, Map<String, Boolean>> outer = new HashMap<>(server.overworld().getData(type));
        String playerKey = playerUuid.toString();
        Map<String, Boolean> inner = new HashMap<>(outer.getOrDefault(playerKey, Map.of()));
        String maidKey = maidUuid.toString();
        if (setItem) {
            inner.put(maidKey, itemValue);
        } else {
            inner.remove(maidKey);
        }
        // 空名单时移除外层键，避免残留空壳（与旧存档清单轻量化惯例一致）
        if (inner.isEmpty()) {
            outer.remove(playerKey);
        } else {
            outer.put(playerKey, inner);
        }
        server.overworld().setData(type, outer);
    }

    /** 顶层 map 读取（view-only，缺省空 map；不安装默认壳） */
    private static Map<String, Boolean> getMap(MinecraftServer server,
                                               Supplier<AttachmentType<Map<String, Boolean>>> type) {
        return server.overworld().getExistingData(type).orElse(Map.of());
    }

    /** 顶层字符串 map 读取（view-only，缺省空 map；不安装默认壳） */
    private static Map<String, String> getStringMap(MinecraftServer server,
                                                    Supplier<AttachmentType<Map<String, String>>> type) {
        return server.overworld().getExistingData(type).orElse(Map.of());
    }

    /** 某玩家单只名单当前条数（容量上限检查用；名单只存非默认项，无则 0） */
    public static int countMaidOverrides(MinecraftServer server,
                                         Supplier<AttachmentType<Map<String, Map<String, Boolean>>>> type,
                                         UUID playerUuid) {
        return server.overworld().getExistingData(type)
                .map(m -> m.getOrDefault(playerUuid.toString(), Map.of()).size())
                .orElse(0);
    }

    /**
     * 某玩家单只 Prompt 条数（容量上限检查用；与布尔名单各自计数，上限一致）。
     * 注意不能与布尔版重载同名：泛型擦除后签名相同，编译器直接报名称冲突。
     */
    public static int countMaidPromptEntries(MinecraftServer server,
                                             Supplier<AttachmentType<Map<String, Map<String, String>>>> type,
                                             UUID playerUuid) {
        return server.overworld().getExistingData(type)
                .map(m -> m.getOrDefault(playerUuid.toString(), Map.of()).size())
                .orElse(0);
    }
}
