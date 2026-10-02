package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.IMaidContext;
import com.maidmod.selftalk.mixin.GameContextRegisterAccessor;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 环境上下文的服务端权威设置计算：有效模式、功能可用性与设置快照。
 * <p>
 * 三层语义必须分开，任何一层都不覆盖另一层：
 * <ol>
 *   <li><b>玩家偏好</b>（{@link PlayerSettingsStore#getEnvironmentContextMode}）：管理员临时关闸时刻意不改动它，
 *       功能恢复后原选择应继续生效；</li>
 *   <li><b>管理员与提供者是否可用</b>（{@link Availability}）：决定界面是否可点、注入侧是否放行；</li>
 *   <li><b>本轮是否有实际数据</b>：只影响本轮候选池，不使界面按钮不可配置。</li>
 * </ol>
 * 第 27 项（附近女仆身份）的可用性必须查<b>服务端注册表内实际存在的 key</b>，
 * 不能用热重载后的 {@code MAID_IDENTITY_ENABLED} 值代替——该功能的注册状态只在启动时决定一次。
 */
public final class EnvironmentContextSettings {

    /**
     * 功能可用性（协议值固定，界面据 availability 选禁用提示文案）。
     * <p>
     * 与「本轮是否有数据」无关：暂无事件、没有着火或没有缺氧都不改变这里的取值。
     */
    public enum Availability {
        /** 功能可用 */
        AVAILABLE(0),
        /** 环境感知总开关关闭 */
        EVENT_MASTER_DISABLED(1),
        /** 玩家受伤感知关闭 */
        HURT_DISABLED(2),
        /** 女仆自身感知关闭 */
        SELF_DISABLED(3),
        /** 附近女仆身份提供者未注册 */
        IDENTITY_NOT_REGISTERED(4),
        /** 其他所需 TLM 提供者缺失 */
        PROVIDER_MISSING(5);

        private final byte id;

        Availability(int id) {
            this.id = (byte) id;
        }

        /** 协议值 */
        public byte id() {
            return id;
        }

        /** 按协议值解析，非法值按「提供者缺失」处理（最保守：不可点、不注入） */
        public static Availability fromId(byte id) {
            for (Availability value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return PROVIDER_MISSING;
        }

        /** 是否可在界面配置（管理员与提供者层面均放行） */
        public boolean configurable() {
            return this == AVAILABLE;
        }
    }

    private EnvironmentContextSettings() {
    }

    // ===== 有效模式 =====

    /** 目录默认模式表（不读存档，管理员关闸与无主女仆都用它；不可变，避免调用方改动污染全局） */
    public static Map<String, EnvironmentContextMode> defaultModes() {
        return DEFAULT_MODES;
    }

    private static final Map<String, EnvironmentContextMode> DEFAULT_MODES = buildDefaultModes();

    private static Map<String, EnvironmentContextMode> buildDefaultModes() {
        Map<String, EnvironmentContextMode> modes = new LinkedHashMap<>();
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            modes.put(option.key(), option.defaultMode());
        }
        return java.util.Collections.unmodifiableMap(modes);
    }

    /**
     * 该玩家的有效模式表（全部 32 项）。
     * <p>
     * 「允许玩家自定义配置」关闭时一律使用目录默认值——玩家覆盖被忽略（但存档里的选择保留，
     * 重新开启后原样生效；见 {@link PlayerSettingsStore#getEnvironmentContextMode}）。
     * 无主女仆（{@code ownerUuid == null}）同样使用默认值：没有玩家偏好可查。
     * <p>
     * 覆盖表只在开头读一次，再在内存中补默认值：一次请求里同一份存档不应被反复取用。
     */
    public static Map<String, EnvironmentContextMode> effectiveModes(MinecraftServer server, UUID ownerUuid) {
        boolean usePlayerPreference = ownerUuid != null && Config.PLAYER_OPTION_ENABLED.get();
        Map<String, EnvironmentContextMode> overrides = usePlayerPreference
                ? PlayerSettingsStore.getEnvironmentContextOverrides(server, ownerUuid)
                : Map.of();
        if (overrides.isEmpty()) {
            return defaultModes();
        }
        // 覆盖表只在开头读一次，再在内存中补默认值：一次请求里同一份存档不应被反复取用
        Map<String, EnvironmentContextMode> modes = new LinkedHashMap<>();
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            modes.put(option.key(), overrides.getOrDefault(option.key(), option.defaultMode()));
        }
        return modes;
    }

    // ===== 可用性 =====

    /**
     * 功能可用性判定（与玩家偏好无关，也与本轮有无数据无关）。
     * <p>
     * 管理员 {@code PLAYER_OPTION_ENABLED} 不在此处参与：它是「整页不可编辑」的总闸，
     * 由协议层的 adminEnabled 字段单独下发，逐行改写 availability 会丢失禁用原因。
     */
    public static Availability availabilityOf(EnvironmentContextOption option) {
        return switch (option.gate()) {
            case PROVIDER -> hasProvider(option.key()) ? Availability.AVAILABLE : Availability.PROVIDER_MISSING;
            case IDENTITY_PROVIDER -> hasProvider(option.key())
                    ? Availability.AVAILABLE : Availability.IDENTITY_NOT_REGISTERED;
            case EVENT_MASTER -> Config.EVENT_CONTEXT_ENABLED.get()
                    ? Availability.AVAILABLE : Availability.EVENT_MASTER_DISABLED;
            case EVENT_HURT -> !Config.EVENT_CONTEXT_ENABLED.get()
                    ? Availability.EVENT_MASTER_DISABLED
                    : (Config.EVENT_CONTEXT_HURT_ENABLED.get() ? Availability.AVAILABLE : Availability.HURT_DISABLED);
            case EVENT_SELF -> !Config.EVENT_CONTEXT_ENABLED.get()
                    ? Availability.EVENT_MASTER_DISABLED
                    : (Config.EVENT_CONTEXT_SELF_ENABLED.get() ? Availability.AVAILABLE : Availability.SELF_DISABLED);
        };
    }

    /** 全部 32 项的可用性表（界面快照与注入侧共用同一判定口） */
    public static Map<String, Availability> availabilityMap() {
        Map<String, Availability> result = new LinkedHashMap<>();
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            result.put(option.key(), availabilityOf(option));
        }
        return result;
    }

    // ===== TLM 提供者访问 =====

    /**
     * 服务端注册表中是否存在该 key。
     * <p>
     * 直接读 {@code GameContextRegister.CONTEXTS}（私有静态表的只读 Accessor）：
     * 其他 mod 的附属注册也在这张表里，因此判定的是「实际注册结果」而非配置意图。
     */
    public static boolean hasProvider(String key) {
        return key != null && GameContextRegisterAccessor.maid_self_talk$contexts().containsKey(key);
    }

    /** 按 key 取提供者，缺失返回 null（只读，禁止改写返回的注册表内容） */
    public static IMaidContext provider(String key) {
        return key == null ? null : GameContextRegisterAccessor.maid_self_talk$contexts().get(key);
    }

    /** 目录项的本地化名称键（界面用；模型上下文一律不出现该键） */
    public static String nameKey(EnvironmentContextOption option) {
        return "config.maid_self_talk.screen.player_settings.context.entry." + option.key();
    }

    /** 上游固定 prompt 分类的 id 列表（保持注册顺序） */
    public static java.util.List<String> promptCategoryIds() {
        return GameContextRegister.allPromptCategories().stream().map(category -> category.id()).toList();
    }
}
