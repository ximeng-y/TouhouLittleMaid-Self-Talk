package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.api.event.client.AddClothConfigEvent;
import com.maidmod.selftalk.Config;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 管理员全局配置界面（Cloth Config）。
 * <p>
 * 通过 TLM 的公开扩展点 {@link AddClothConfigEvent} 挂到「AI 全局设置」分类下，
 * 结构为「女仆自言自语」父分类，内含「态 1」「态 2」「欢迎语」「自话提示词」子页。
 * <b>注意：</b>本类引用了 cloth 客户端类，只能在安装了 cloth-config 时加载，
 * 因此不使用 @EventBusSubscriber 自动注册，改由主类在运行时判断后反射注册。
 */
@OnlyIn(Dist.CLIENT)
public final class SelfTalkClothConfig {

    private SelfTalkClothConfig() {
    }

    @SubscribeEvent
    public static void onAddClothConfig(AddClothConfigEvent event) {
        ConfigBuilder root = event.getRoot();
        ConfigEntryBuilder entryBuilder = event.getEntryBuilder();
        // 挂到 TLM 的 AI 全局设置分类下
        ConfigCategory globalAi = root.getOrCreateCategory(
                Component.translatable("config.touhou_little_maid.global_ai"));

        // 父分类：女仆自言自语
        SubCategoryBuilder main = entryBuilder.startSubCategory(
                        Component.translatable("config.maid_self_talk.title"))
                .setExpanded(true);
        main.add(entryBuilder.startBooleanToggle(Component.translatable("config.maid_self_talk.enabled"),
                        Config.ENABLED.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.maid_self_talk.enabled.tooltip"))
                .setSaveConsumer(v -> saveBool(Config.ENABLED, v))
                .build());
        main.add(entryBuilder.startBooleanToggle(Component.translatable("config.maid_self_talk.player_option_enabled"),
                        Config.PLAYER_OPTION_ENABLED.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.maid_self_talk.player_option_enabled.tooltip"))
                .setSaveConsumer(v -> saveBool(Config.PLAYER_OPTION_ENABLED, v))
                .build());

        // 子页：态 1 / 态 2
        main.add(stateCategory(entryBuilder, "config.maid_self_talk.state_owner_online",
                Config.STATE1_ENABLED, Config.STATE1_MIN_INTERVAL, Config.STATE1_MAX_INTERVAL,
                Config.STATE1_PLAYER_RANGE, Config.STATE1_KEEP_SELF_TALK_COUNT).build());
        main.add(stateCategory(entryBuilder, "config.maid_self_talk.state_owner_offline",
                Config.STATE2_ENABLED, Config.STATE2_MIN_INTERVAL, Config.STATE2_MAX_INTERVAL,
                Config.STATE2_PLAYER_RANGE, Config.STATE2_KEEP_SELF_TALK_COUNT).build());

        // 子页：欢迎语
        SubCategoryBuilder welcome = entryBuilder.startSubCategory(
                        Component.translatable("config.maid_self_talk.welcome"))
                .setExpanded(false);
        welcome.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.maid_self_talk.welcome.enabled"),
                        Config.WELCOME_ENABLED.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.maid_self_talk.welcome.enabled.tooltip"))
                .setSaveConsumer(v -> saveBool(Config.WELCOME_ENABLED, v))
                .build());
        welcome.add(entryBuilder.startIntSlider(
                        Component.translatable("config.maid_self_talk.welcome.window_ticks"),
                        Config.WELCOME_WINDOW_TICKS.get(), 20, 72000)
                .setDefaultValue(600)
                .setTooltip(Component.translatable("config.maid_self_talk.welcome.window_ticks.tooltip"))
                .setSaveConsumer(v -> saveInt(Config.WELCOME_WINDOW_TICKS, v))
                .build());
        main.add(welcome.build());

        // 子页：自话提示词
        SubCategoryBuilder prompt = entryBuilder.startSubCategory(
                        Component.translatable("config.maid_self_talk.prompt"))
                .setExpanded(false);
        prompt.add(entryBuilder.startTextField(
                        Component.translatable("config.maid_self_talk.prompt.self_talk"),
                        Config.SELF_TALK_PROMPT.get())
                .setTooltip(Component.translatable("config.maid_self_talk.prompt.self_talk.tooltip"))
                .setSaveConsumer(v -> Config.SELF_TALK_PROMPT.set(v))
                .build());
        prompt.add(entryBuilder.startTextField(
                        Component.translatable("config.maid_self_talk.prompt.self_talk_owner_nearby"),
                        Config.SELF_TALK_PROMPT_OWNER_NEARBY.get())
                .setTooltip(Component.translatable("config.maid_self_talk.prompt.self_talk_owner_nearby.tooltip"))
                .setSaveConsumer(v -> Config.SELF_TALK_PROMPT_OWNER_NEARBY.set(v))
                .build());
        prompt.add(entryBuilder.startTextField(
                        Component.translatable("config.maid_self_talk.prompt.welcome"),
                        Config.WELCOME_PROMPT.get())
                .setTooltip(Component.translatable("config.maid_self_talk.prompt.welcome.tooltip"))
                .setSaveConsumer(v -> Config.WELCOME_PROMPT.set(v))
                .build());
        prompt.add(entryBuilder.startStrField(
                        Component.translatable("config.maid_self_talk.prompt.language"),
                        Config.SELF_TALK_LANGUAGE.get())
                .setTooltip(Component.translatable("config.maid_self_talk.prompt.language.tooltip"))
                .setSaveConsumer(v -> Config.SELF_TALK_LANGUAGE.set(v))
                .build());
        main.add(prompt.build());

        globalAi.addEntry(main.build());
    }

    private static SubCategoryBuilder stateCategory(ConfigEntryBuilder entryBuilder, String key,
                                                    ModConfigSpec.BooleanValue enabled,
                                                    ModConfigSpec.IntValue minInterval,
                                                    ModConfigSpec.IntValue maxInterval,
                                                    ModConfigSpec.DoubleValue range,
                                                    ModConfigSpec.IntValue keepCount) {
        SubCategoryBuilder builder = entryBuilder.startSubCategory(Component.translatable(key))
                .setExpanded(false);
        builder.add(entryBuilder.startBooleanToggle(Component.translatable(key + ".enabled"),
                        enabled.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable(key + ".enabled.tooltip"))
                .setSaveConsumer(v -> saveBool(enabled, v))
                .build());
        builder.add(entryBuilder.startIntField(Component.translatable(key + ".min_interval"),
                        minInterval.get())
                .setMin(10).setMax(86400)
                .setDefaultValue(60)
                .setTooltip(Component.translatable(key + ".min_interval.tooltip"))
                .setSaveConsumer(v -> saveInt(minInterval, v))
                .build());
        builder.add(entryBuilder.startIntField(Component.translatable(key + ".max_interval"),
                        maxInterval.get())
                .setMin(10).setMax(86400)
                .setDefaultValue(300)
                .setTooltip(Component.translatable(key + ".max_interval.tooltip"))
                .setSaveConsumer(v -> saveInt(maxInterval, v))
                .build());
        builder.add(entryBuilder.startDoubleField(Component.translatable(key + ".player_range"),
                        range.get())
                .setMin(1.0).setMax(512.0)
                .setDefaultValue(16.0)
                .setTooltip(Component.translatable(key + ".player_range.tooltip"))
                .setSaveConsumer(v -> saveDouble(range, v))
                .build());
        builder.add(entryBuilder.startIntSlider(Component.translatable(key + ".keep_self_talk_count"),
                        keepCount.get(), 1, 50)
                .setDefaultValue(5)
                .setTooltip(Component.translatable(key + ".keep_self_talk_count.tooltip"))
                .setSaveConsumer(v -> saveInt(keepCount, v))
                .build());
        return builder;
    }

    private static void saveBool(ModConfigSpec.BooleanValue spec, boolean value) {
        spec.set(value);
        spec.save();
    }

    private static void saveInt(ModConfigSpec.IntValue spec, int value) {
        spec.set(value);
        spec.save();
    }

    private static void saveDouble(ModConfigSpec.DoubleValue spec, double value) {
        spec.set(value);
        spec.save();
    }
}
