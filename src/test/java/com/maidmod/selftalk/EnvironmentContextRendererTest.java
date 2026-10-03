package com.maidmod.selftalk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link EnvironmentContextRenderer} 与 {@link ContextLanguage} 的自检入口
 * （无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * 渲染类刻意不依赖 Minecraft / TLM 类型，本类因此可以用 {@code javac + java} 单独编译执行，
 * 不必启动游戏：
 * <pre>
 * javac -d out src/main/java/com/maidmod/selftalk/ContextLanguage.java \
 *       src/main/java/com/maidmod/selftalk/SelfTalkEventBuffer.java \
 *       src/main/java/com/maidmod/selftalk/SelfTalkPrompts.java \
 *       src/main/java/com/maidmod/selftalk/EnvironmentContextOption.java \
 *       src/main/java/com/maidmod/selftalk/EnvironmentContextMode.java \
 *       src/main/java/com/maidmod/selftalk/EnvironmentContextRenderer.java \
 *       src/test/java/com/maidmod/selftalk/EnvironmentContextRendererTest.java
 * java -cp out com.maidmod.selftalk.EnvironmentContextRendererTest
 * </pre>
 * Gradle 的 {@code test} 任务不含 JUnit 用例，不会执行本入口，需显式调用。
 */
public final class EnvironmentContextRendererTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID ZOMBIE = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    /** 与 ZOMBIE 同名但 UUID 不同 */
    private static final UUID ZOMBIE_TWIN = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final UUID SKELETON = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID MAID_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000f");

    private static int checks = 0;

    private EnvironmentContextRendererTest() {
    }

    public static void main(String[] args) {
        timeOrWeatherAlone();
        timeAndWeatherMerged();
        themesSegmented();
        emptyEntriesProduceNothing();
        explicitNegativeValues();
        ownerMissingSemantics();
        englishPossessive();
        itemNamesSurviveEscaping();
        duplicateStacksKept();
        nearbyEntitiesKeepDistanceDropId();
        identityOffNearestOn();
        effectsSnapshot();
        languageSelection();
        adjacentSameSourceRepeated();
        sameNameDifferentUuidNotMerged();
        notMergedAcrossMiddle();
        languageSwitchKeepsFactsAndTime();
        englishSecondPlural();
        System.out.println("EnvironmentContextRendererTest passed: " + checks + " checks");
    }

    /** 1. 只有时间或只有天气：独立成句，不出现悬空连接词 */
    private static void timeOrWeatherAlone() {
        String timeOnly = assemble(List.of(entry("game_time", "06:30")));
        check(timeOnly.equals("当前游戏时间是 06:30。"), "只有时间应独立成句，实际：" + timeOnly);
        check(!timeOnly.contains("，"), "只有时间不应出现逗号连接词");

        String weatherOnly = assemble(List.of(entry("weather", "Raining")));
        check(weatherOnly.equals("当前世界天气为雨天。"), "只有天气应独立成句，实际：" + weatherOnly);
        check(!weatherOnly.contains("，"), "只有天气不应出现逗号连接词");

        String en = assemble(List.of(entry("weather", "Sunny", ContextLanguage.EN)));
        check(en.equals("The world's weather is sunny."), "英文天气句，实际：" + en);
    }

    /** 2. 时间与天气同时存在：合为一句，每项只出现一次 */
    private static void timeAndWeatherMerged() {
        String zh = assemble(List.of(entry("game_time", "06:30"), entry("weather", "Raining")));
        check(zh.equals("当前游戏时间是 06:30，世界天气为雨天。"), "中英合句，实际：" + zh);
        check(count(zh, "06:30") == 1 && count(zh, "雨天") == 1, "合句后每项只应出现一次");
        check(!zh.contains("。当前世界天气"), "合句后不应再出现独立的天气句");

        String en = assemble(List.of(
                entry("game_time", "06:30", ContextLanguage.EN),
                entry("weather", "Raining", ContextLanguage.EN)));
        check(en.equals("The in-game time is 06:30, and the world's weather is rainy."),
                "英文合句，实际：" + en);

        // 中间隔了别的主题（这里用不相邻的排列模拟）：不合并，避免把天气挪到中间条目之前
        List<EnvironmentContextRenderer.Entry> separated = List.of(
                entry("game_time", "06:30"), entry("nearby_entities", "你附近目前没有任何生物。"),
                entry("weather", "Raining"));
        String joined = assemble(separated);
        check(joined.contains("当前游戏时间是 06:30。") && joined.contains("当前世界天气为雨天。"),
                "不相邻的时间与天气不应合句，实际：" + joined);
    }

    /** 3. 世界信息与主人信息同时存在：按主题分段，不跨主题强行连成长句 */
    private static void themesSegmented() {
        String text = assemble(List.of(
                entry("game_time", "06:30"), entry("weather", "Raining"),
                entry("user_healthy", "18.0 (max 20.0)"),
                entry("distance_to_user", "4.2")));
        String[] lines = text.split("\n");
        check(lines.length == 2, "世界段与主人段应各占一行，实际 " + lines.length + " 行：" + text);
        check(lines[0].contains("06:30") && lines[0].contains("雨天"), "第一行是世界事实");
        check(lines[1].contains("18.0") && lines[1].contains("4.2"), "第二行是主人事实");
        check(lines[0].endsWith("。") && lines[1].endsWith("。"), "两段各自成句");
    }

    /** 4. 全部关闭或无数据：不生成空标题、空句子或伪造事实 */
    private static void emptyEntriesProduceNothing() {
        check(EnvironmentContextRenderer.assemble(List.of()).isEmpty(), "空条目应返回空串");
        check(EnvironmentContextRenderer.assemble(null).isEmpty(), "null 条目应返回空串");
        check(EnvironmentContextRenderer.renderEvents(List.of(), 100, ContextLanguage.ZH).isEmpty(),
                "无事件应返回空串");
        check(!EnvironmentContextRenderer.assemble(List.of()).contains("\n"), "空结果不应含换行");
    }

    /** 5. 明确 None/Empty/no/not：按 key 语义表达，不统一删除 */
    private static void explicitNegativeValues() {
        check(EnvironmentContextRenderer.simpleField("sleep_state", "no", ContextLanguage.ZH)
                .equals("你现在没有在睡觉。"), "no → 当前没有睡觉");
        check(EnvironmentContextRenderer.simpleField("sitting", "no", ContextLanguage.ZH)
                .equals("你现在没有坐着。"), "sitting no");
        check(EnvironmentContextRenderer.simpleField("follow_state", "yes", ContextLanguage.ZH)
                .equals("你现在处于跟随模式。"), "follow_state 的 yes 表示跟随模式已启用（上游为反向判断）");
        check(EnvironmentContextRenderer.simpleField("follow_state", "no", ContextLanguage.ZH)
                .equals("你现在没有处于跟随模式。"), "follow_state 的 no 表示未处于跟随模式");
        check(!EnvironmentContextRenderer.simpleField("follow_state", "yes", ContextLanguage.ZH)
                .contains("正在"), "只描述模式是否启用，不宣称实体此刻正在移动");
        check(EnvironmentContextRenderer.simpleField("riding", "not", ContextLanguage.ZH)
                .equals("你现在没有骑乘任何东西。"), "riding not");
        check(EnvironmentContextRenderer.simpleField("riding", "not", ContextLanguage.EN)
                .equals("You are not riding anything right now."), "riding not (en)");
        check(EnvironmentContextRenderer.simpleField("riding", "riding minecraft:horse", ContextLanguage.ZH)
                .contains("minecraft:horse"), "riding 保留坐骑类型标识");
        check(EnvironmentContextRenderer.items("mainhand_item", List.of(), false, ContextLanguage.ZH)
                .equals("你现在主手没有拿任何东西。"), "主手为空应明说");
        check(EnvironmentContextRenderer.items("inventory_items", List.of(), false, ContextLanguage.ZH)
                .equals("你的背包是空的。"), "空背包应明说");
        check(EnvironmentContextRenderer.items("armor_items", List.of(), false, ContextLanguage.ZH)
                .equals("你没有穿戴装备。"), "未穿装备应明说");
        check(EnvironmentContextRenderer.effects(List.of(), ContextLanguage.ZH)
                .equals("你身上没有任何状态效果。"), "无效果应明说");
        check(EnvironmentContextRenderer.nearbyEntities(List.of(), ContextLanguage.ZH)
                .equals("你附近目前没有任何生物。"), "附近无生物应明说");
    }

    /** 6. 无主人实体：主人血量不进入候选；未知位置/装备不被写成无主人或裸装 */
    private static void ownerMissingSemantics() {
        // 采集侧在取不到主人时返回 null（= 无数据），渲染侧对 None 的措辞必须是「目前无法获取」
        check(EnvironmentContextRenderer.simpleField("user_position", "None", ContextLanguage.ZH)
                .equals("目前无法获取你的主人所在的方块坐标。"), "None 位置应说无法获取");
        check(EnvironmentContextRenderer.simpleField("distance_to_user", "None", ContextLanguage.ZH)
                .equals("目前无法获取你与主人之间的距离。"), "None 距离应说无法获取");
        check(!EnvironmentContextRenderer.simpleField("distance_to_user", "None", ContextLanguage.ZH)
                .contains("没有主人"), "取不到距离不能说成没有主人");
        check(EnvironmentContextRenderer.simpleField("user_name", "Master (Chinese is '主人')",
                ContextLanguage.ZH).contains("Master (Chinese is '主人')"),
                "缺省主人称呼是兜底称呼，原样保留");

        // 英文主语的 be 动词必须与主语一致（主语只可能是 you 与 your owner 两种）
        check(EnvironmentContextRenderer.simpleField("healthy", "18.0 (max 20.0)", ContextLanguage.EN)
                .startsWith("You have 18.0"), "自己生命值用 You have");
        check(EnvironmentContextRenderer.simpleField("user_healthy", "14.0 (max 20.0)", ContextLanguage.EN)
                .startsWith("Your owner has 14.0"), "主人生命值用 Your owner has");
        check(EnvironmentContextRenderer.simpleField("self_position", "98, 63, -229", ContextLanguage.EN)
                .startsWith("You are at"), "自己坐标用 You are at");
        check(EnvironmentContextRenderer.simpleField("user_position", "102, 64, -233", ContextLanguage.EN)
                .startsWith("Your owner is at"), "主人坐标用 Your owner is at");

        // 上游对「主人取不到」与「栏位为空」返回同一个常量，两者必须分开表达：
        // 前者是「无法获取装备信息」，后者是「当前没有装备」，都不能写成「你没有主人」
        String unavailable = EnvironmentContextRenderer.items("user_armor", List.of(), true, ContextLanguage.ZH);
        check(unavailable.contains("无法获取") && !unavailable.contains("没有穿戴"),
                "取不到主人装备信息时不得说成裸装，实际：" + unavailable);
        check(!unavailable.contains("没有主人"), "取不到装备信息不能说成没有主人");
        String wearing = EnvironmentContextRenderer.items("user_armor", List.of(), false, ContextLanguage.ZH);
        check(wearing.equals("你的主人没有穿戴装备。"), "主人可取但没装备应明确说没有穿戴");
    }

    /** 6b. 英文非空持物：所有格必须与主格区分，自己用 Your 而不是 You's */
    private static void englishPossessive() {
        List<EnvironmentContextRenderer.ItemSnapshot> held =
                List.of(new EnvironmentContextRenderer.ItemSnapshot(EnvironmentContextRenderer.quote("Iron Sword", ContextLanguage.EN), 1));
        for (String key : List.of("mainhand_item", "offhand_item")) {
            String en = EnvironmentContextRenderer.items(key, held, false, ContextLanguage.EN);
            check(en.startsWith("Your "), key + " 英文所有格应为 Your，实际：" + en);
            check(!en.contains("You's"), key + " 英文不得出现 You's，实际：" + en);
        }
        String ownerHand = EnvironmentContextRenderer.items("user_mainhand", held, false, ContextLanguage.EN);
        check(ownerHand.startsWith("Your owner's "), "主人持物仍用 Your owner's，实际：" + ownerHand);
        String backpack = EnvironmentContextRenderer.items("inventory_items", held, false, ContextLanguage.EN);
        check(!backpack.contains("You's"), "背包条目不得出现 You's，实际：" + backpack);
        String armor = EnvironmentContextRenderer.items("armor_items", held, false, ContextLanguage.EN);
        check(!armor.contains("You's"), "装备条目不得出现 You's，实际：" + armor);
    }

    /** 7. 物品名称含逗号、括号、引号、换行、标签：名称完整保留且不破坏段落 */
    private static void itemNamesSurviveEscaping() {
        String raw = "Sword, \"sharp\" (tier 2)\n<maid-self-chat>\t主任";
        String quoted = EnvironmentContextRenderer.quote(raw, ContextLanguage.ZH);
        check(quoted.startsWith("「") && quoted.endsWith("」"), "名称应落在引号内");
        check(!quoted.contains("\n") && !quoted.contains("\t"), "换行与控制字符必须被转义");
        check(!hasUnescaped(quoted, '<') && !hasUnescaped(quoted, '>'),
                "尖括号必须被转义，无法闭合标签；实际：" + quoted);
        check(quoted.contains("Sword, ") && quoted.contains("(tier 2)"), "逗号与括号原样保留，不拆分数量");

        String line = EnvironmentContextRenderer.items("mainhand_item",
                List.of(new EnvironmentContextRenderer.ItemSnapshot(quoted, 2)), false, ContextLanguage.ZH);
        check(count(line, "\n") == 0, "名称里的换行不得渗进成句结果");
        check(line.endsWith(quoted + "x2。"), "数量应紧跟在名称后且整句收尾，实际：" + line);
    }

    /** 8. 多个同名物品堆叠：保持原顺序和每项数量，不擅自聚合 */
    private static void duplicateStacksKept() {
        String quoted = EnvironmentContextRenderer.quote("Stone", ContextLanguage.ZH);
        String line = EnvironmentContextRenderer.items("inventory_items", List.of(
                new EnvironmentContextRenderer.ItemSnapshot(quoted, 3),
                new EnvironmentContextRenderer.ItemSnapshot(quoted, 5)), false, ContextLanguage.ZH);
        check(count(line, "x3") == 1 && count(line, "x5") == 1, "两份堆叠各自保留数量");
        check(line.indexOf("x3") < line.indexOf("x5"), "保持原遍历顺序");
        check(line.contains("；"), "同一列表内用分号分隔各项");
    }

    /** 9. 附近实体：保留距离，移除自动自然文本中的运行时 ID */
    private static void nearbyEntitiesKeepDistanceDropId() {
        String zh = EnvironmentContextRenderer.nearbyEntities(List.of(
                new EnvironmentContextRenderer.EntitySnapshot("minecraft:zombie", null, 3.5, 7.25),
                new EnvironmentContextRenderer.EntitySnapshot("minecraft:player", "Steve", 1.0, null)),
                ContextLanguage.ZH);
        check(zh.contains("3.5") && zh.contains("7.3"), "两种距离都保留（保留一位小数）");
        check(zh.contains("minecraft:zombie"), "实体类型标识保留");
        check(zh.contains("Steve"), "玩家名称保留");
        check(!zh.contains("id=") && !zh.contains("entity_id"), "运行时实体编号必须移除");

        String en = EnvironmentContextRenderer.nearbyEntities(List.of(
                new EnvironmentContextRenderer.EntitySnapshot("minecraft:zombie", null, 3.5, null)),
                ContextLanguage.EN);
        check(en.contains("3.5 blocks from you"), "英文距离带单位，实际：" + en);
        check(!en.contains("blocks from your owner"), "无主人时不应编造到主人的距离");
    }

    /** 10. 身份项关闭但附近实体开启：不额外输出身份 UUID（项与项之间互不影响） */
    private static void identityOffNearestOn() {
        // 附近实体的自然文本里没有任何 UUID；身份文本里的 UUID 只在其自身被选中时出现
        String nearby = EnvironmentContextRenderer.nearbyEntities(List.of(
                new EnvironmentContextRenderer.EntitySnapshot("minecraft:zombie", null, 2.0, null)),
                ContextLanguage.ZH);
        check(!nearby.contains(MAID_UUID.toString()), "附近实体文本不含身份 UUID");
        String identity = EnvironmentContextRenderer.identities(List.of(
                new EnvironmentContextRenderer.IdentitySnapshot("Sakuya", MAID_UUID, 42)), ContextLanguage.ZH);
        check(identity.contains(MAID_UUID.toString()) && !identity.contains("42"),
                "身份项保留 UUID 且移除 entity_id");
        check(identity.contains("Sakuya"), "身份项保留名称");
    }

    /** 11. 效果：等级、tick、无限时长及不显示粒子/图标均正确 */
    private static void effectsSnapshot() {
        String zh = EnvironmentContextRenderer.effects(List.of(
                new EnvironmentContextRenderer.EffectSnapshot("effect.minecraft.speed", 2, 600, false, true, true),
                new EnvironmentContextRenderer.EffectSnapshot("effect.minecraft.night_vision", 1, 0, true, false, false)),
                ContextLanguage.ZH);
        check(zh.contains("等级 2") && zh.contains("剩余 600 tick"), "等级与剩余 tick，实际：" + zh);
        check(zh.contains("无限时长"), "无限时长单独说明");
        check(zh.contains("不显示粒子") && zh.contains("不显示图标"), "保留显示标志");
        check(zh.contains("；"), "多条效果用分号分隔");
        // 只对第二条说了「不显示粒子」，第一条不应被连累
        check(count(zh, "不显示粒子") == 1, "显示标志逐条独立");
    }

    /** 12. 中文与非中文语言标签：正确选择 ZH/EN，且不改变输出语言要求 */
    private static void languageSelection() {
        for (String zh : List.of("zh", "zh_cn", "zh_tw", "zh_hk")) {
            check(ContextLanguage.of(zh) == ContextLanguage.ZH, "中文标签应选 ZH：" + zh);
        }
        // 合法标签的大小写由 sanitizeLanguage 原样放行，输出语言指令经 Locale 仍识别为中文，
        // 背景若按大小写区分就会选成英文——同一份标签必须给出同一种模板语言
        for (String zh : List.of("ZH", "ZH_CN", "Zh_TW", "zH_Hk")) {
            check(ContextLanguage.of(zh) == ContextLanguage.ZH, "中文标签大小写不应影响判定：" + zh);
        }
        for (String other : List.of("en_us", "ja_jp", "ko_kr", "fr")) {
            check(ContextLanguage.of(other) == ContextLanguage.EN, "非中文标签应选 EN：" + other);
        }
        for (String other : List.of("EN_US", "Ja_JP", "KO_kr")) {
            check(ContextLanguage.of(other) == ContextLanguage.EN, "非中文标签大小写不应误判为中文：" + other);
        }
        // 非法/缺失标签的既有回退不在这里重测（那是 SelfTalkContexts.sanitizeLanguage 的职责，
        // 本类只接受已校验的标签）；这里确认语言选择不反向影响中文模板的默认取值
        check(ContextLanguage.ZH.isZh() && !ContextLanguage.EN.isZh(), "isZh 判定与枚举一致");
    }

    /** 13. 相邻同源受伤：使用「多次」/multiple times，最近时间取最后一条 */
    private static void adjacentSameSourceRepeated() {
        List<SelfTalkEventBuffer.Event> events = new ArrayList<>();
        events.add(selfHurt(0, "Skeleton", SelfTalkEventBuffer.Source.ATTACKER));
        events.add(selfHurt(20, "Skeleton", SelfTalkEventBuffer.Source.ATTACKER));
        String zh = EnvironmentContextRenderer.renderEvents(events, 40, ContextLanguage.ZH);
        check(zh.contains("多次"), "相邻同源应归并并说「多次」，实际：" + zh);
        check(!zh.contains("连续"), "不得使用「连续多次」一类措辞");
        check(!zh.contains("2 次") && !zh.contains("2次"), "不得输出精确受击次数");
        check(zh.contains("最近一次约 1 秒前"), "最近一次时间取组内最后一条（20 tick → 1 秒）");

        String en = EnvironmentContextRenderer.renderEvents(events, 40, ContextLanguage.EN);
        check(en.contains("multiple times"), "英文用 multiple times，实际：" + en);
        check(!en.toLowerCase().contains("continuously"), "英文不得使用 continuously");
        check(en.contains("most recently about 1 second ago"), "英文最近时间单数形式");
    }

    /** 14. 同名但 UUID 不同：既不误去重也不误归并 */
    private static void sameNameDifferentUuidNotMerged() {
        List<SelfTalkEventBuffer.Event> events = List.of(
                selfHurtBy(ZOMBIE, 0, "Zombie"),
                selfHurtBy(ZOMBIE_TWIN, 20, "Zombie"));
        String text = EnvironmentContextRenderer.renderEvents(events, 40, ContextLanguage.ZH);
        check(!text.contains("多次"), "同名不同 UUID 的攻击者不得合并");
        check(count(text, "Zombie") == 2, "两条记录应各自渲染");
    }

    /** 15. A→B→A：不跨中间记录合并 */
    private static void notMergedAcrossMiddle() {
        List<SelfTalkEventBuffer.Event> events = List.of(
                selfHurt(0, "Zombie", SelfTalkEventBuffer.Source.ATTACKER),
                playerHurt(20, ALICE, SKELETON),
                selfHurt(40, "Zombie", SelfTalkEventBuffer.Source.ATTACKER));
        String text = EnvironmentContextRenderer.renderEvents(events, 60, ContextLanguage.ZH);
        check(!text.contains("多次"), "非相邻的同来源记录不得被合并");
        check(count(text, "Zombie") == 2, "两段 Zombie 各自渲染");
        check(text.contains("你的主人"), "中间记录仍在");
    }

    /** 16. 入队后切换语言：用新语言模板，事实与时间不变 */
    private static void languageSwitchKeepsFactsAndTime() {
        List<SelfTalkEventBuffer.Event> events = List.of(
                death(0, ALICE, "Alice 被僵尸杀死", "Alice was slain by Zombie"));
        long nowTick = 40;
        String zh = EnvironmentContextRenderer.renderEvents(events, nowTick, ContextLanguage.ZH);
        String en = EnvironmentContextRenderer.renderEvents(events, nowTick, ContextLanguage.EN);
        check(zh.contains("Alice 被僵尸杀死") && zh.contains("约 2 秒前"), "中文模板，实际：" + zh);
        check(en.contains("Alice was slain by Zombie") && en.contains("About 2 seconds ago"),
                "英文模板（句首大写），实际：" + en);
    }

    /** 17. 单秒与多秒英文：1 second / N seconds */
    private static void englishSecondPlural() {
        check(EnvironmentContextRenderer.relativeTime(20, ContextLanguage.EN).equals("about 1 second ago"),
                "1 秒用单数");
        check(EnvironmentContextRenderer.relativeTime(40, ContextLanguage.EN).equals("about 2 seconds ago"),
                "2 秒用复数");
        check(EnvironmentContextRenderer.relativeTime(19, ContextLanguage.EN).equals("just now"),
                "不足 20 tick 用 just now");
        check(EnvironmentContextRenderer.relativeTime(19, ContextLanguage.ZH).equals("刚才"),
                "不足 20 tick 用刚才");
    }

    // ===== 辅助 =====

    /** 以 key 走渲染器取句子并带上必要从句，模拟 SelfTalkContexts 的组装 */
    private static EnvironmentContextRenderer.Entry entry(String key, String raw) {
        return entry(key, raw, ContextLanguage.ZH);
    }

    private static EnvironmentContextRenderer.Entry entry(String key, String raw, ContextLanguage lang) {
        if ("game_time".equals(key)) {
            return EnvironmentContextRenderer.Entry.mergeable(key,
                    EnvironmentContextRenderer.gameTimeSentence(raw, lang),
                    EnvironmentContextRenderer.gameTimeClause(raw, lang));
        }
        if ("weather".equals(key)) {
            return EnvironmentContextRenderer.Entry.mergeable(key,
                    EnvironmentContextRenderer.weatherSentence(raw, lang),
                    EnvironmentContextRenderer.weatherClause(raw, lang));
        }
        String sentence = EnvironmentContextRenderer.simpleField(key, raw, lang);
        if (sentence != null) {
            return EnvironmentContextRenderer.Entry.of(key, sentence);
        }
        // 复杂列表在测试里直接给成品句子（采集侧已渲染）
        return EnvironmentContextRenderer.Entry.of(key, raw);
    }

    private static String assemble(List<EnvironmentContextRenderer.Entry> entries) {
        return EnvironmentContextRenderer.assemble(entries);
    }

    private static SelfTalkEventBuffer.Event selfHurt(long tick, String attackerName,
                                                      SelfTalkEventBuffer.Source source) {
        return selfHurtBy(ZOMBIE, tick, attackerName);
    }

    private static SelfTalkEventBuffer.Event selfHurtBy(UUID attacker, long tick, String attackerName) {
        return new SelfTalkEventBuffer.Event(SelfTalkEventBuffer.Kind.SELF_HURT, tick, BOB, attacker,
                "minecraft:mob_attack",
                new SelfTalkEventBuffer.HurtFact(SelfTalkEventBuffer.Subject.SELF, null, attackerName,
                        SelfTalkEventBuffer.Source.ATTACKER));
    }

    private static SelfTalkEventBuffer.Event playerHurt(long tick, UUID subject, UUID attacker) {
        return new SelfTalkEventBuffer.Event(SelfTalkEventBuffer.Kind.PLAYER_HURT, tick, subject, attacker,
                "minecraft:mob_attack",
                new SelfTalkEventBuffer.HurtFact(SelfTalkEventBuffer.Subject.OWNER, "Alice", "Skeleton",
                        SelfTalkEventBuffer.Source.ATTACKER));
    }

    private static SelfTalkEventBuffer.Event death(long tick, UUID subject, String zhText, String enText) {
        return new SelfTalkEventBuffer.Event(SelfTalkEventBuffer.Kind.DEATH, tick, subject, ZOMBIE,
                "minecraft:player_attack", new SelfTalkEventBuffer.DeathFact(zhText, enText));
    }

    private static int count(String text, String needle) {
        int found = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            found++;
            index = text.indexOf(needle, index + needle.length());
        }
        return found;
    }

    /** 文本中是否存在未被反斜杠转义的该字符 */
    private static boolean hasUnescaped(String text, char target) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target && (i == 0 || text.charAt(i - 1) != '\\')) {
                return true;
            }
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }
}
