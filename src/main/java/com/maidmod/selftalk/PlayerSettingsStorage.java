package com.maidmod.selftalk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

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
}
