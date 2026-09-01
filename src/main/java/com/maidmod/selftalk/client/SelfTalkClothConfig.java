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

@OnlyIn(Dist.CLIENT)
public final class SelfTalkClothConfig {

    private SelfTalkClothConfig() {
    }

    @SubscribeEvent
    public static void onAddClothConfig(AddClothConfigEvent event) {
        ConfigBuilder root = event.getRoot();
        ConfigEntryBuilder entryBuilder = event.getEntryBuilder();
        ConfigCategory globalAi = root.getOrCreateCategory(
                Component.translatable("config.touhou_little_maid.global_ai"));

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
        main.add(entryBuilder.startBooleanToggle(Component.translatable("config.maid_self_talk.tool_call_enabled"),
                        Config.TOOL_CALL_ENABLED.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.maid_self_talk.tool_call_enabled.tooltip"))
                .setSaveConsumer(v -> saveBool(Config.TOOL_CALL_ENABLED, v))
                .build());

        main.add(stateCategory(entryBuilder, "config.maid_self_talk.state_owner_online",
                Config.STATE1_ENABLED, Config.STATE1_MIN_INTERVAL, Config.STATE1_MAX_INTERVAL,
                Config.STATE1_PLAYER_RANGE, Config.STATE1_KEEP_SELF_TALK_COUNT,
                60, 300, 16.0, 5).build());
        main.add(stateCategory(entryBuilder, "config.maid_self_talk.state_owner_offline",
                Config.STATE2_ENABLED, Config.STATE2_MIN_INTERVAL, Config.STATE2_MAX_INTERVAL,
                Config.STATE2_PLAYER_RANGE, Config.STATE2_KEEP_SELF_TALK_COUNT,
                120, 600, 32.0, 3).build());

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

        SubCategoryBuilder prompt = entryBuilder.startSubCategory(
                        Component.translatable("config.maid_self_talk.prompt"))
                .setExpanded(false);
        prompt.add(entryBuilder.startStrField(
                        Component.translatable("config.maid_self_talk.prompt.language"),
                        Config.SELF_TALK_LANGUAGE.get())
                .setDefaultValue("zh_cn")
                .setTooltip(Component.translatable("config.maid_self_talk.prompt.language.tooltip"))
                .setSaveConsumer(v -> saveString(Config.SELF_TALK_LANGUAGE, v))
                .build());
        main.add(prompt.build());

        SubCategoryBuilder interChat = entryBuilder.startSubCategory(
                        Component.translatable("config.maid_self_talk.inter_chat"))
                .setExpanded(false);
        interChat.add(entryBuilder.startBooleanToggle(Component.translatable("config.maid_self_talk.inter_chat.enabled"),
                        Config.INTER_CHAT_ENABLED.get())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.maid_self_talk.inter_chat.enabled.tooltip"))
                .setSaveConsumer(v -> saveBool(Config.INTER_CHAT_ENABLED, v))
                .build());
        interChat.add(entryBuilder.startIntField(Component.translatable("config.maid_self_talk.inter_chat.min_interval"),
                        Config.INTER_CHAT_MIN_INTERVAL.get())
                .setMin(10).setMax(86400)
                .setDefaultValue(300)
                .setTooltip(Component.translatable("config.maid_self_talk.inter_chat.min_interval.tooltip"))
                .setSaveConsumer(v -> saveInt(Config.INTER_CHAT_MIN_INTERVAL, v))
                .build());
        interChat.add(entryBuilder.startIntField(Component.translatable("config.maid_self_talk.inter_chat.max_interval"),
                        Config.INTER_CHAT_MAX_INTERVAL.get())
                .setMin(10).setMax(86400)
                .setDefaultValue(600)
                .setTooltip(Component.translatable("config.maid_self_talk.inter_chat.max_interval.tooltip"))
                .setSaveConsumer(v -> saveInt(Config.INTER_CHAT_MAX_INTERVAL, v))
                .build());
        interChat.add(entryBuilder.startDoubleField(Component.translatable("config.maid_self_talk.inter_chat.player_range"),
                        Config.INTER_CHAT_PLAYER_RANGE.get())
                .setMin(1.0).setMax(512.0)
                .setDefaultValue(16.0)
                .setTooltip(Component.translatable("config.maid_self_talk.inter_chat.player_range.tooltip"))
                .setSaveConsumer(v -> saveDouble(Config.INTER_CHAT_PLAYER_RANGE, v))
                .build());
        interChat.add(entryBuilder.startDoubleField(Component.translatable("config.maid_self_talk.inter_chat.maid_range"),
                        Config.INTER_CHAT_MAID_RANGE.get())
                .setMin(1.0).setMax(512.0)
                .setDefaultValue(8.0)
                .setTooltip(Component.translatable("config.maid_self_talk.inter_chat.maid_range.tooltip"))
                .setSaveConsumer(v -> saveDouble(Config.INTER_CHAT_MAID_RANGE, v))
                .build());
        interChat.add(entryBuilder.startIntSlider(Component.translatable("config.maid_self_talk.inter_chat.keep_rounds"),
                        Config.INTER_CHAT_KEEP_ROUNDS.get(), 1, 50)
                .setDefaultValue(5)
                .setTooltip(Component.translatable("config.maid_self_talk.inter_chat.keep_rounds.tooltip"))
                .setSaveConsumer(v -> saveInt(Config.INTER_CHAT_KEEP_ROUNDS, v))
                .build());
        interChat.add(entryBuilder.startDoubleField(Component.translatable("config.maid_self_talk.inter_chat.chain_probability"),
                        Config.INTER_CHAT_CHAIN_PROBABILITY.get())
                .setMin(0.0).setMax(1.0)
                .setDefaultValue(0.3)
                .setTooltip(Component.translatable("config.maid_self_talk.inter_chat.chain_probability.tooltip"))
                .setSaveConsumer(v -> saveDouble(Config.INTER_CHAT_CHAIN_PROBABILITY, v))
                .build());
        interChat.add(entryBuilder.startIntSlider(Component.translatable("config.maid_self_talk.inter_chat.max_chain_rounds"),
                        Config.INTER_CHAT_MAX_CHAIN_ROUNDS.get(), 1, 50)
                .setDefaultValue(10)
                .setTooltip(Component.translatable("config.maid_self_talk.inter_chat.max_chain_rounds.tooltip"))
                .setSaveConsumer(v -> saveInt(Config.INTER_CHAT_MAX_CHAIN_ROUNDS, v))
                .build());
        main.add(interChat.build());

        globalAi.addEntry(main.build());
    }

    private static SubCategoryBuilder stateCategory(ConfigEntryBuilder entryBuilder, String key,
                                                    ModConfigSpec.BooleanValue enabled,
                                                    ModConfigSpec.IntValue minInterval,
                                                    ModConfigSpec.IntValue maxInterval,
                                                    ModConfigSpec.DoubleValue range,
                                                    ModConfigSpec.IntValue keepCount,
                                                    int defaultMin, int defaultMax,
                                                    double defaultRange, int defaultKeep) {
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
                .setDefaultValue(defaultMin)
                .setTooltip(Component.translatable(key + ".min_interval.tooltip"))
                .setSaveConsumer(v -> saveInt(minInterval, v))
                .build());
        builder.add(entryBuilder.startIntField(Component.translatable(key + ".max_interval"),
                        maxInterval.get())
                .setMin(10).setMax(86400)
                .setDefaultValue(defaultMax)
                .setTooltip(Component.translatable(key + ".max_interval.tooltip"))
                .setSaveConsumer(v -> saveInt(maxInterval, v))
                .build());
        builder.add(entryBuilder.startDoubleField(Component.translatable(key + ".player_range"),
                        range.get())
                .setMin(1.0).setMax(512.0)
                .setDefaultValue(defaultRange)
                .setTooltip(Component.translatable(key + ".player_range.tooltip"))
                .setSaveConsumer(v -> saveDouble(range, v))
                .build());
        builder.add(entryBuilder.startIntSlider(Component.translatable(key + ".keep_self_talk_count"),
                        keepCount.get(), 1, 50)
                .setDefaultValue(defaultKeep)
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

    private static void saveString(ModConfigSpec.ConfigValue<String> spec, String value) {
        spec.set(value);
        spec.save();
    }
}
