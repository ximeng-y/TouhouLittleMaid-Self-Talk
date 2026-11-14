package com.maidmod.selftalk;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 偷听聊天的世界存档载体（overworld SavedData，名称 {@code maid_self_talk_overheard}）。
 * <p>
 * 文件为 {@code <世界根>/data/maid_self_talk_overheard.dat}；正文只在集中表存一份，
 * 女仆引用记录只存编号。挂 overworld 的 {@code getDataStorage()}，所有维度共用同一份。
 * <p>
 * 所有读写都在服务端主线程；每次持久变更后立即置脏，沿用原生自动保存、手动保存与正常停服保存。
 * 不在 {@code LevelEvent.Save} 里临时搬数据——两线该事件都在 DataStorage 本轮保存之后触发。
 */
public final class OverheardChatSavedData extends SavedData {

    private static final String NAME = "maid_self_talk_overheard";

    private static final String TAG_LAST_ID = "LastAssignedId";
    private static final String TAG_MESSAGES = "Messages";
    private static final String TAG_BUFFER = "RecentBuffer";
    private static final String TAG_MAIDS = "Maids";

    private static final String TAG_ID = "Id";
    private static final String TAG_PLAYER = "Player";
    private static final String TAG_NAME = "Name";
    private static final String TAG_TIME = "Time";
    private static final String TAG_BODY = "Body";
    private static final String TAG_SOURCE = "Source";
    private static final String TAG_ANCHORS = "Anchors";
    private static final String TAG_EFFECTIVE = "Effective";
    private static final String TAG_ARCHIVE = "Archive";
    private static final String TAG_GROUP = "Group";
    private static final String TAG_FILL_NEXT = "FillNext";
    private static final String TAG_MAID_UUID = "Maid";

    private final OverheardChatStore store = new OverheardChatStore();
    /** 上次与存档对齐的持久状态快照：用于判断是否有变更需要置脏 */
    private OverheardChatStore.State lastSaved;

    private OverheardChatSavedData() {
    }

    /** 固定从 overworld 取（每个维度有独立 DataStorage，只有 overworld 是永驻单实例） */
    public static OverheardChatSavedData get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OverheardChatSavedData::new, OverheardChatSavedData::load), NAME);
    }

    /** 本世界唯一的偷听 store；取不到世界时返回 null */
    public static OverheardChatStore storeOf(MinecraftServer server) {
        OverheardChatSavedData data = get(server);
        return data == null ? null : data.store;
    }

    /** NeoForge 加载签名：附带注册表访问器 */
    private static OverheardChatSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        OverheardChatSavedData data = new OverheardChatSavedData();
        data.store.restore(readState(tag));
        data.lastSaved = data.store.snapshotForSave();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        OverheardChatStore.State state = store.snapshotForSave();
        writeState(tag, state);
        lastSaved = state;
        return tag;
    }

    /** 持久状态有变化时置脏（调用点：采集、提交、摘要退出、清空、停服关闭） */
    public void markDirtyIfChanged() {
        OverheardChatStore.State state = store.snapshotForSave();
        if (lastSaved == null || !lastSaved.equals(state)) {
            lastSaved = state;
            setDirty();
        }
    }

    // ===== NBT 编解码 =====

    private static OverheardChatStore.State readState(CompoundTag tag) {
        long lastAssignedId = tag.contains(TAG_LAST_ID) ? tag.getLong(TAG_LAST_ID)
                : OverheardChatStore.ID_START;
        List<OverheardChatStore.Message> messages = new ArrayList<>();
        ListTag messageList = tag.getList(TAG_MESSAGES, Tag.TAG_COMPOUND);
        for (Tag element : messageList) {
            CompoundTag entry = (CompoundTag) element;
            UUID player = readUuid(entry, TAG_PLAYER);
            if (player == null) {
                continue;
            }
            OverheardChatStore.Source source;
            try {
                source = OverheardChatStore.Source.valueOf(entry.getString(TAG_SOURCE));
            } catch (IllegalArgumentException e) {
                source = OverheardChatStore.Source.NORMAL_CHAT;
            }
            messages.add(new OverheardChatStore.Message(entry.getLong(TAG_ID), player,
                    entry.getString(TAG_NAME), entry.getLong(TAG_TIME), entry.getString(TAG_BODY), source));
        }
        List<Long> buffer = new ArrayList<>();
        for (Tag element : tag.getList(TAG_BUFFER, Tag.TAG_LONG)) {
            buffer.add(((LongTag) element).getAsLong());
        }
        Map<UUID, OverheardChatStore.MaidState> maids = new LinkedHashMap<>();
        for (Tag element : tag.getList(TAG_MAIDS, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            UUID maidUuid = readUuid(entry, TAG_MAID_UUID);
            if (maidUuid == null) {
                continue;
            }
            List<Long> anchors = readLongList(entry, TAG_ANCHORS);
            List<Long> effective = readLongList(entry, TAG_EFFECTIVE);
            List<List<Long>> archive = new ArrayList<>();
            for (Tag groupElement : entry.getList(TAG_ARCHIVE, Tag.TAG_COMPOUND)) {
                List<Long> group = readLongList((CompoundTag) groupElement, TAG_GROUP);
                if (!group.isEmpty()) {
                    archive.add(group);
                }
            }
            maids.put(maidUuid, new OverheardChatStore.MaidState(anchors, effective, archive,
                    entry.contains(TAG_FILL_NEXT) ? entry.getLong(TAG_FILL_NEXT) : -1));
        }
        return new OverheardChatStore.State(lastAssignedId, messages, buffer, maids);
    }

    private static void writeState(CompoundTag tag, OverheardChatStore.State state) {
        tag.putLong(TAG_LAST_ID, state.lastAssignedId());
        ListTag messageList = new ListTag();
        for (OverheardChatStore.Message message : state.messages()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong(TAG_ID, message.id());
            entry.putUUID(TAG_PLAYER, message.playerUuid());
            entry.putString(TAG_NAME, message.playerName() == null ? "" : message.playerName());
            entry.putLong(TAG_TIME, message.epochMillis());
            entry.putString(TAG_BODY, message.body() == null ? "" : message.body());
            entry.putString(TAG_SOURCE, message.source().name());
            messageList.add(entry);
        }
        tag.put(TAG_MESSAGES, messageList);
        ListTag buffer = new ListTag();
        for (Long id : state.recentBuffer()) {
            buffer.add(LongTag.valueOf(id));
        }
        tag.put(TAG_BUFFER, buffer);
        ListTag maidList = new ListTag();
        for (Map.Entry<UUID, OverheardChatStore.MaidState> entry : state.maids().entrySet()) {
            OverheardChatStore.MaidState saved = entry.getValue();
            CompoundTag maidTag = new CompoundTag();
            maidTag.putUUID(TAG_MAID_UUID, entry.getKey());
            maidTag.put(TAG_ANCHORS, longListTag(saved.anchors()));
            maidTag.put(TAG_EFFECTIVE, longListTag(saved.effective()));
            ListTag archive = new ListTag();
            for (List<Long> group : saved.archive()) {
                CompoundTag groupTag = new CompoundTag();
                groupTag.put(TAG_GROUP, longListTag(group));
                archive.add(groupTag);
            }
            maidTag.put(TAG_ARCHIVE, archive);
            if (saved.fillNextId() > 0) {
                maidTag.putLong(TAG_FILL_NEXT, saved.fillNextId());
            }
            maidList.add(maidTag);
        }
        tag.put(TAG_MAIDS, maidList);
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) {
            return null;
        }
        try {
            return tag.getUUID(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Long> readLongList(CompoundTag tag, String key) {
        List<Long> ids = new ArrayList<>();
        for (Tag element : tag.getList(key, Tag.TAG_LONG)) {
            ids.add(((LongTag) element).getAsLong());
        }
        return ids;
    }

    private static ListTag longListTag(List<Long> ids) {
        ListTag list = new ListTag();
        for (Long id : ids) {
            list.add(LongTag.valueOf(id));
        }
        return list;
    }
}
