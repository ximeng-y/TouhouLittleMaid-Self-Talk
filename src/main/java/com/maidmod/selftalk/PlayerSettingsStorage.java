package com.maidmod.selftalk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 玩家独立设置存储（Forge 1.20.1 无 attachment 系统）。
 * <p>
 * 数据写入 persistentData 的 PERSISTED_NBT_TAG（"PlayerPersisted"）子标签：
 * Forge 的 ServerPlayer.restoreFrom 会将该子标签从旧玩家复制到新玩家，
 * 因此设置跨死亡重生自动保留；该子标签随玩家 NBT 存档，服务端重启亦保留。
 */
public final class PlayerSettingsStorage {

    /** 命名空间化 key，避免与其他 mod 在共享子标签内撞键 */
    private static final String KEY = "maid_self_talk:enabled";

    /** 单只关闭名单：女仆 UUID 字符串列表（仅存关闭项，恢复时移除） */
    private static final String DISABLED_MAIDS_KEY = "maid_self_talk:disabled_maids";

    /** 单只关闭名单最大条数：防止恶意客户端无限写入撑爆玩家 NBT（主线程 contains 亦为 O(n) 扫描） */
    private static final int MAX_DISABLED_MAIDS = 256;

    private PlayerSettingsStorage() {
    }

    /** 默认启用：未设置过时返回 true */
    public static boolean isEnabled(Player player) {
        CompoundTag sub = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        // 必须显式 contains：getBoolean 对缺失 key 返回 false，直接调用会翻转默认值
        return !sub.contains(KEY) || sub.getBoolean(KEY);
    }

    public static void setEnabled(Player player, boolean enabled) {
        CompoundTag persisted = player.getPersistentData();
        // getCompound 对缺失 key 返回新空 tag 且不写回原 tag，必须先 contains 判断再 put
        if (!persisted.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)) {
            persisted.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        persisted.getCompound(Player.PERSISTED_NBT_TAG).putBoolean(KEY, enabled);
    }

    /** 单只关闭名单是否包含该女仆（缺省 = 跟随全局，不关闭） */
    public static boolean isMaidDisabled(Player player, UUID maidUuid) {
        CompoundTag sub = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        ListTag list = sub.getList(DISABLED_MAIDS_KEY, Tag.TAG_STRING);
        return list.contains(StringTag.valueOf(maidUuid.toString()));
    }

    /** 设置单只关闭：关闭去重后追加，恢复时移除；名单为空时删除 key 避免空 tag 残留 */
    public static void setMaidDisabled(Player player, UUID maidUuid, boolean disabled) {
        CompoundTag persisted = player.getPersistentData();
        if (!persisted.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)) {
            persisted.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        CompoundTag sub = persisted.getCompound(Player.PERSISTED_NBT_TAG);
        ListTag list = sub.getList(DISABLED_MAIDS_KEY, Tag.TAG_STRING);
        String uuid = maidUuid.toString();
        if (disabled) {
            if (!list.contains(StringTag.valueOf(uuid))) {
                // 超限拒绝写入：恶意客户端可高速发送 Set 包无限追加任意 UUID，
                // 上限兜底名单体积（NBT 膨胀）与主线程 O(n) 扫描开销
                if (list.size() >= MAX_DISABLED_MAIDS) {
                    return;
                }
                list.add(StringTag.valueOf(uuid));
            }
        } else {
            list.removeIf(tag -> tag.getAsString().equals(uuid));
        }
        if (list.isEmpty()) {
            sub.remove(DISABLED_MAIDS_KEY);
        } else {
            sub.put(DISABLED_MAIDS_KEY, list);
        }
    }
}
