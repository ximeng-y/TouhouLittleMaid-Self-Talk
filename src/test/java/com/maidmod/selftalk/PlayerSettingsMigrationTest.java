package com.maidmod.selftalk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.UUID;

/**
 * Forge 玩家设置迁移自检（无测试框架依赖，直接 {@code main} 跑，issue #122）。
 * <p>
 * 覆盖：空目标组迁移旧值；有效新组优先不被覆盖且清洗旧键；目标名单损坏保留原始 NBT、
 * 迁移受阻、保存透传、显式单只名单写入替换解除、全局开关不清除损坏名单；旧布尔/旧名单
 * 非法时不部分写入且保留旧键；空名单外层键按无有效数据处理；重复迁移幂等。
 * 经包内 {@code PlayerSettingsSavedData.load} 与 {@code SelfTalkMigration.migrateSettings}
 * 构造调用，不启动服务器。已由 {@code gradle check} 真实执行，失败即构建失败。
 */
public final class PlayerSettingsMigrationTest {

    private static int checks = 0;

    private static final String OLD_ENABLED = "maid_self_talk:enabled";
    private static final String OLD_DISABLED_MAIDS = "maid_self_talk:disabled_maids";
    private static final String OLD_INTER_CHAT_ENABLED = "maid_self_talk:inter_chat_enabled";
    private static final String OLD_INTER_CHAT_DISABLED_MAIDS = "maid_self_talk:inter_chat_disabled_maids";

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MAID_A = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID MAID_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final String PLAYER_KEY = PLAYER.toString();

    private PlayerSettingsMigrationTest() {
    }

    public static void main(String[] args) {
        migrateFreshGroup();
        validNewGroupTakesPrecedence();
        corruptedTargetList();
        corruptedGroupIsolation();
        invalidOldDataNoPartialWrite();
        emptyListOuterKey();
        System.out.println("PlayerSettingsMigrationTest passed: " + checks + " checks");
    }

    /** 1. 空目标组：旧布尔与名单完整迁移、旧键删除；重复迁移幂等且不覆盖已迁移值 */
    private static void migrateFreshGroup() {
        PlayerSettingsSavedData data = PlayerSettingsSavedData.load(new CompoundTag());
        CompoundTag sub = legacySub(OLD_ENABLED, OLD_DISABLED_MAIDS, true, List.of(MAID_A.toString()));
        SelfTalkMigration.migrateSettings(data, PLAYER, sub);
        check(data.getSelfTalkEnabled(PLAYER), "旧布尔应迁移到 SavedData");
        check(data.isSelfTalkMaidDisabled(PLAYER, MAID_A), "旧名单应迁移为单只关闭项");
        check(!data.isSelfTalkMaidDisabled(PLAYER, MAID_B), "未列入的 maid 不应被关闭");
        check(!sub.contains(OLD_ENABLED) && !sub.contains(OLD_DISABLED_MAIDS),
                "迁移成功后应删除对应两项旧键");
        // 幂等：再次带旧键登录（残留清洗），已迁移值不被二次覆盖
        CompoundTag again = legacySub(OLD_ENABLED, OLD_DISABLED_MAIDS, false, List.of(MAID_B.toString()));
        SelfTalkMigration.migrateSettings(data, PLAYER, again);
        check(data.getSelfTalkEnabled(PLAYER), "重复迁移不得覆盖已迁移值");
        check(!data.isSelfTalkMaidDisabled(PLAYER, MAID_B), "重复迁移不得合并新名单");
        check(!again.contains(OLD_ENABLED) && !again.contains(OLD_DISABLED_MAIDS),
                "重复迁移应清洗残留旧键");
        // 保存 → 重新加载：迁移结果完整持久化
        PlayerSettingsSavedData reloaded = PlayerSettingsSavedData.load(data.save(new CompoundTag()));
        check(reloaded.getSelfTalkEnabled(PLAYER) && reloaded.isSelfTalkMaidDisabled(PLAYER, MAID_A),
                "迁移结果应随存档保留");
    }

    /** 2. 有效新组优先：已有有效值时旧值不覆盖、旧键仅清洗 */
    private static void validNewGroupTakesPrecedence() {
        PlayerSettingsSavedData data = PlayerSettingsSavedData.load(new CompoundTag());
        data.setSelfTalkEnabled(PLAYER, false);
        CompoundTag sub = legacySub(OLD_ENABLED, OLD_DISABLED_MAIDS, true, List.of(MAID_A.toString()));
        SelfTalkMigration.migrateSettings(data, PLAYER, sub);
        check(!data.getSelfTalkEnabled(PLAYER), "有效新组不应被旧值覆盖");
        check(!data.isSelfTalkMaidDisabled(PLAYER, MAID_A), "有效新组存在时不得并入旧名单");
        check(!sub.contains(OLD_ENABLED) && !sub.contains(OLD_DISABLED_MAIDS),
                "有效新组替代后应清洗旧键");
    }

    /** 3. 目标名单损坏：保留原始 NBT、迁移受阻、保存透传、显式单只写入替换解除 */
    private static void corruptedTargetList() {
        CompoundTag stored = new CompoundTag();
        CompoundTag overrides = new CompoundTag();
        overrides.putString(PLAYER_KEY, "损坏为字符串的名单");
        stored.put("selfTalkMaidOverrides", overrides);
        PlayerSettingsSavedData data = PlayerSettingsSavedData.load(stored);
        check(data.hasCorruptedSelfTalkMaidList(PLAYER), "损坏名单应标记迁移受阻");
        check(!data.hasSelfTalkData(PLAYER), "损坏名单不计入有效数据");
        // 迁移受阻：旧键整组保留、不部分写入
        CompoundTag sub = legacySub(OLD_ENABLED, OLD_DISABLED_MAIDS, true, List.of(MAID_A.toString()));
        SelfTalkMigration.migrateSettings(data, PLAYER, sub);
        check(sub.contains(OLD_ENABLED) && sub.contains(OLD_DISABLED_MAIDS),
                "目标组受阻应保留该组全部旧键");
        check(data.getSelfTalkEnabled(PLAYER) && !data.isSelfTalkMaidDisabled(PLAYER, MAID_A),
                "受阻时不得迁移任何旧值");
        // 保存 → 重新加载：原始损坏条目原样透传、仍受阻
        PlayerSettingsSavedData reloaded = PlayerSettingsSavedData.load(data.save(new CompoundTag()));
        check(reloaded.hasCorruptedSelfTalkMaidList(PLAYER), "损坏条目应随存档透传保留");
        // 全局开关不影响损坏名单
        reloaded.setSelfTalkEnabled(PLAYER, false);
        check(reloaded.hasCorruptedSelfTalkMaidList(PLAYER), "单独改变全局开关不得清除损坏名单");
        // 显式单只名单写入：以新输入替换损坏名单并解除标记
        reloaded.setSelfTalkMaidDisabled(PLAYER, MAID_A, true);
        check(!reloaded.hasCorruptedSelfTalkMaidList(PLAYER), "显式单只写入应解除受阻标记");
        check(reloaded.isSelfTalkMaidDisabled(PLAYER, MAID_A), "显式写入应正常生效");
        PlayerSettingsSavedData fixed = PlayerSettingsSavedData.load(reloaded.save(new CompoundTag()));
        check(!fixed.hasCorruptedSelfTalkMaidList(PLAYER) && fixed.isSelfTalkMaidDisabled(PLAYER, MAID_A),
                "替换后的名单应正常持久化");
    }

    /** 4. 组间隔离：仅互聊名单损坏时，自话组照常迁移，互聊旧键保留 */
    private static void corruptedGroupIsolation() {
        CompoundTag stored = new CompoundTag();
        CompoundTag overrides = new CompoundTag();
        overrides.putString(PLAYER_KEY, "损坏");
        stored.put("interChatMaidOverrides", overrides);
        PlayerSettingsSavedData data = PlayerSettingsSavedData.load(stored);
        CompoundTag sub = legacySub(OLD_ENABLED, OLD_DISABLED_MAIDS, true, List.of(MAID_A.toString()));
        putList(sub, OLD_INTER_CHAT_DISABLED_MAIDS, List.of(MAID_B.toString()));
        SelfTalkMigration.migrateSettings(data, PLAYER, sub);
        check(data.getSelfTalkEnabled(PLAYER) && data.isSelfTalkMaidDisabled(PLAYER, MAID_A),
                "自话组不受互聊名单损坏影响，应照常迁移");
        check(data.hasCorruptedInterChatMaidList(PLAYER), "互聊名单应标记受阻");
        check(sub.contains(OLD_INTER_CHAT_DISABLED_MAIDS), "受阻组的旧键应保留");
    }

    /** 5. 旧数据非法：不部分写入、保留旧键（布尔为字符串 / 名单元素非 UUID / 名单类型错误） */
    private static void invalidOldDataNoPartialWrite() {
        PlayerSettingsSavedData data = PlayerSettingsSavedData.load(new CompoundTag());
        // 旧布尔是字符串
        CompoundTag sub = new CompoundTag();
        sub.putString(OLD_ENABLED, "true");
        putList(sub, OLD_DISABLED_MAIDS, List.of(MAID_A.toString()));
        SelfTalkMigration.migrateSettings(data, PLAYER, sub);
        check(data.getSelfTalkEnabled(PLAYER) && !data.isSelfTalkMaidDisabled(PLAYER, MAID_A),
                "旧布尔非法时不得部分写入（名单也不迁移）");
        check(sub.contains(OLD_ENABLED) && sub.contains(OLD_DISABLED_MAIDS), "非法旧值应保留旧键");
        // 名单元素不是 UUID 字符串
        PlayerSettingsSavedData data2 = PlayerSettingsSavedData.load(new CompoundTag());
        CompoundTag sub2 = legacySub(OLD_ENABLED, OLD_DISABLED_MAIDS, true, List.of(MAID_A.toString()));
        putList(sub2, OLD_DISABLED_MAIDS, List.of(MAID_A.toString(), "不是UUID"));
        SelfTalkMigration.migrateSettings(data2, PLAYER, sub2);
        check(data2.getSelfTalkEnabled(PLAYER) && !data2.isSelfTalkMaidDisabled(PLAYER, MAID_A),
                "旧名单含非法元素时不得部分写入（布尔也不迁移）");
        check(sub2.contains(OLD_ENABLED) && sub2.contains(OLD_DISABLED_MAIDS), "非法旧名单应保留旧键");
        // 名单本身不是列表
        PlayerSettingsSavedData data3 = PlayerSettingsSavedData.load(new CompoundTag());
        CompoundTag sub3 = new CompoundTag();
        sub3.putBoolean(OLD_ENABLED, false);
        sub3.putString(OLD_DISABLED_MAIDS, "不是列表");
        SelfTalkMigration.migrateSettings(data3, PLAYER, sub3);
        check(data3.getSelfTalkEnabled(PLAYER) && !data3.isSelfTalkMaidDisabled(PLAYER, MAID_A),
                "旧名单类型错误时不得部分写入");
        check(sub3.contains(OLD_ENABLED) && sub3.contains(OLD_DISABLED_MAIDS), "类型错误的旧名单应保留旧键");
        // 名单元素类型错误（整数）
        PlayerSettingsSavedData data4 = PlayerSettingsSavedData.load(new CompoundTag());
        CompoundTag sub4 = legacySub(OLD_ENABLED, OLD_DISABLED_MAIDS, true, List.of(MAID_A.toString()));
        ListTag badList = new ListTag();
        badList.add(net.minecraft.nbt.IntTag.valueOf(1));
        sub4.put(OLD_DISABLED_MAIDS, badList);
        SelfTalkMigration.migrateSettings(data4, PLAYER, sub4);
        check(data4.getSelfTalkEnabled(PLAYER), "元素类型错误的旧名单应整组保留不迁移");
    }

    /** 6. 空名单外层键：按无有效数据处理，迁移照常进行 */
    private static void emptyListOuterKey() {
        CompoundTag stored = new CompoundTag();
        CompoundTag overrides = new CompoundTag();
        overrides.put(PLAYER_KEY, new ListTag());
        stored.put("selfTalkMaidOverrides", overrides);
        PlayerSettingsSavedData data = PlayerSettingsSavedData.load(stored);
        check(!data.hasSelfTalkData(PLAYER), "空名单不应计入有效数据");
        CompoundTag sub = legacySub(OLD_ENABLED, OLD_DISABLED_MAIDS, true, List.of(MAID_A.toString()));
        SelfTalkMigration.migrateSettings(data, PLAYER, sub);
        check(data.getSelfTalkEnabled(PLAYER) && data.isSelfTalkMaidDisabled(PLAYER, MAID_A),
                "空名单目标组应照常迁移旧值");
    }

    // ===== 辅助 =====

    /** 构造旧 persistentData 子标签：布尔 + 字符串名单 */
    private static CompoundTag legacySub(String enabledKey, String listKey, boolean enabled,
                                         List<String> maids) {
        CompoundTag sub = new CompoundTag();
        sub.putBoolean(enabledKey, enabled);
        putList(sub, listKey, maids);
        return sub;
    }

    private static void putList(CompoundTag sub, String key, List<String> values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        sub.put(key, list);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("PlayerSettingsMigrationTest: " + message);
        }
    }
}
