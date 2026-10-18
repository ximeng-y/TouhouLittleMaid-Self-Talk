package com.maidmod.selftalk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 预制复用库存状态自检（无测试框架依赖，直接 {@code main} 跑，issue #122）。
 * <p>
 * 覆盖：只读闩锁下追加/更新/标记全部不变；删除先失败后成功可重试；持续失败后重建
 * store 仍按同一排序（createdAt 升序、UUID 字符串升序）重算限容；旧格式/损坏文件不阻止
 * 好条目保存且原件不变；写失败不删除旧文件、原文件不被破坏；删除边界预期运行时故障
 * 转为单条失败。故障状态经包内构造入口（注入库路径、容量来源、删除操作）与反射注入，
 * 不依赖系统权限变更或真实文件锁。已由 {@code gradle check} 真实执行，失败即构建失败。
 */
public final class SelfTalkReuseStoreTest {

    private static int checks = 0;

    /** 恒定指纹（满足 v1 目录 64 位小写十六进制约束） */
    private static final String FP = "ab".repeat(32);

    private SelfTalkReuseStoreTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("self-talk-reuse-store-test");
        try {
            appendTrimAndDeleteRetry(root.resolve("retry"));
            persistentFailureRecompute(root.resolve("recompute"));
            unreadableGuards(root.resolve("unreadable"));
            badEntriesIsolated(root.resolve("bad"));
            writeFailureKeepsOldFiles(root.resolve("write-fail"));
            deleteRuntimeFailureIsolated(root.resolve("rt-fail"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println("SelfTalkReuseStoreTest passed: " + checks + " checks");
    }

    /** 1. 追加与容量裁剪；删除先失败后成功可重试 */
    private static void appendTrimAndDeleteRetry(Path dir) throws IOException {
        Set<UUID> failIds = new HashSet<>();
        SelfTalkReuseStore store = newStore(dir, 2, failIds);
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID e3 = UUID.randomUUID();
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e1, "26-01-01-00:00:00", "第一条"), 2);
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e2, "26-02-01-00:00:00", "第二条"), 2);
        store.saveDirty();
        checkIds(store, "追加两条后应都在库", e1, e2);
        check(Files.exists(dir.resolve("generated").resolve("welcome")
                .resolve("v1-" + FP).resolve("entry-" + e1 + ".md")), "落盘应写出条目文件");
        // 追加第三条：按 createdAt 升序淘汰 e1，文件删除
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e3, "26-03-01-00:00:00", "第三条"), 2);
        store.saveDirty();
        checkIds(store, "超容量应淘汰最旧", e2, e3);
        check(!Files.exists(dir.resolve("generated").resolve("welcome")
                .resolve("v1-" + FP).resolve("entry-" + e1 + ".md")), "被淘汰条目文件应删除");
        // 删除先失败后成功：e2 被淘汰，删除失败保留文件与重试状态；恢复后重试成功
        UUID e4 = UUID.randomUUID();
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e4, "26-04-01-00:00:00", "第四条"), 2);
        failIds.add(e2);
        store.saveDirty();
        check(Files.exists(dir.resolve("generated").resolve("welcome")
                .resolve("v1-" + FP).resolve("entry-" + e2 + ".md")), "删除失败的文件应保留待重试");
        checkIds(store, "删除失败不影响内存状态", e3, e4);
        failIds.remove(e2);
        store.saveDirty();
        check(!Files.exists(dir.resolve("generated").resolve("welcome")
                .resolve("v1-" + FP).resolve("entry-" + e2 + ".md")), "恢复后重试删除应成功");
    }

    /** 2. 持续删除失败后重建 store：重新扫描加载（含此前未删掉的文件）并按同一排序重算限容 */
    private static void persistentFailureRecompute(Path dir) throws IOException {
        Set<UUID> failIds = new HashSet<>();
        SelfTalkReuseStore first = newStore(dir, 2, failIds);
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID e3 = UUID.randomUUID();
        first.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e1, "26-01-01-00:00:00", "最旧"), 2);
        first.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e2, "26-02-01-00:00:00", "次旧"), 2);
        first.saveDirty();
        // e1 被淘汰但删除持续失败：文件留在磁盘上
        first.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e3, "26-03-01-00:00:00", "最新"), 2);
        failIds.add(e1);
        first.saveDirty();
        check(Files.exists(dir.resolve("generated").resolve("welcome")
                .resolve("v1-" + FP).resolve("entry-" + e1 + ".md")), "持续失败时最旧文件应保留");
        // 重建 store：扫描加载 e1/e2/e3 三条合法新格式条目，重算裁剪仍淘汰最旧的 e1
        failIds.clear();
        SelfTalkReuseStore rebuilt = newStore(dir, 2, failIds);
        checkIds(rebuilt, "重建扫描后裁剪候选不超容量", e2, e3);
        rebuilt.saveDirty();
        check(!Files.exists(dir.resolve("generated").resolve("welcome")
                .resolve("v1-" + FP).resolve("entry-" + e1 + ".md")), "重建重算后应完成淘汰文件删除");
    }

    /** 3. 只读闩锁：追加、更新、标记均不变，entries 返回空快照，不落盘 */
    private static void unreadableGuards(Path dir) throws Exception {
        SelfTalkReuseStore store = newStore(dir, 5, new HashSet<>());
        // 先触发一次空目录扫描建立来源状态，再反射注入只读闩锁
        check(store.entries(FP, SelfTalkReuseStore.SOURCE_WELCOME).isEmpty(), "空目录应扫出空库");
        markUnreadable(store);
        UUID id = UUID.randomUUID();
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(id, "26-01-01-00:00:00", "不应入库"), 5);
        check(store.entries(FP, SelfTalkReuseStore.SOURCE_WELCOME).isEmpty(),
                "只读时追加应放弃且 entries 为空快照");
        SelfTalkReuseStore.PrebuiltEntry phantom =
                new SelfTalkReuseStore.PrebuiltEntry(id, "26-01-01-00:00:00", Map.of(), "zh_cn",
                        "不存在", null, null, SelfTalkReuseStore.Origin.MACHINE);
        check(!store.updateTts(FP, SelfTalkReuseStore.SOURCE_WELCOME, phantom, "新语音", "zh_cn"),
                "只读时 TTS 覆写应返回 false");
        store.saveDirty();
        check(!Files.exists(dir.resolve("generated")), "只读来源不应创建目录或写文件");
    }

    /** 4. 旧格式/损坏文件不阻止好条目保存，原件不变；新格式好条目正常参与保存 */
    private static void badEntriesIsolated(Path dir) throws IOException {
        UUID good = UUID.randomUUID();
        Path sub = dir.resolve("generated").resolve("welcome").resolve("v1-" + FP);
        Files.createDirectories(sub);
        SelfTalkReuseStore.PrebuiltEntry goodEntry =
                new SelfTalkReuseStore.PrebuiltEntry(good, "26-01-01-00:00:00", Map.of(), "zh_cn",
                        "好条目", null, null, SelfTalkReuseStore.Origin.MACHINE);
        String goodContent = PrebuiltEntryCodec.writeMachine(goodEntry);
        String oldSchema = goodContent.replace("schemaVersion: 4", "schemaVersion: 3");
        String corrupted = "---\nschemaVersion: 4\nbodyFormat: \"fenced-v1\"\nentryId: \"不是UUID\"\n";
        String headless = "没有头的旧 custom 正文。";
        Files.writeString(sub.resolve("entry-" + good + ".md"), goodContent);
        Path oldFile = sub.resolve("entry-" + UUID.randomUUID() + ".md");
        Path corruptFile = sub.resolve("entry-" + UUID.randomUUID() + ".md");
        Path headlessFile = sub.resolve("entry-" + UUID.randomUUID() + ".md");
        Files.writeString(oldFile, oldSchema);
        Files.writeString(corruptFile, corrupted);
        Files.writeString(headlessFile, headless);
        SelfTalkReuseStore store = newStore(dir, 5, new HashSet<>());
        List<SelfTalkReuseStore.PrebuiltEntry> entries =
                store.entries(FP, SelfTalkReuseStore.SOURCE_WELCOME);
        check(entries.size() == 1 && entries.get(0).entryId.equals(good),
                "坏条目应被拒用且不阻止好条目加载");
        // 更新好条目并落盘：坏文件原样保留
        check(store.updateTts(FP, SelfTalkReuseStore.SOURCE_WELCOME, entries.get(0), "新语音", "zh_cn"),
                "好条目 TTS 覆写应成功");
        store.saveDirty();
        check(Files.readString(oldFile).equals(oldSchema)
                && Files.readString(corruptFile).equals(corrupted)
                && Files.readString(headlessFile).equals(headless),
                "旧格式与损坏文件应保持原件不变");
        String updated = Files.readString(sub.resolve("entry-" + good + ".md"));
        check(updated.contains("新语音") && !updated.equals(goodContent),
                "好条目应正常写盘更新");
    }

    /** 5. 写失败：不删除淘汰旧文件、原文件不被破坏，恢复后重试成功 */
    private static void writeFailureKeepsOldFiles(Path dir) throws IOException {
        Set<UUID> failIds = new HashSet<>();
        SelfTalkReuseStore store = newStore(dir, 2, failIds);
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID e3 = UUID.randomUUID();
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e1, "26-01-01-00:00:00", "第一条"), 2);
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e2, "26-02-01-00:00:00", "第二条"), 2);
        store.saveDirty();
        Path sub = dir.resolve("generated").resolve("welcome").resolve("v1-" + FP);
        String e2Before = Files.readString(sub.resolve("entry-" + e2 + ".md"));
        // 用同名的临时文件目录占位：e2 的写入必定失败（I/O 故障的确定性替身）
        Files.createDirectories(sub.resolve("entry-" + e2 + ".md.tmp"));
        SelfTalkReuseStore.PrebuiltEntry e2Ref = store.entries(FP, SelfTalkReuseStore.SOURCE_WELCOME)
                .stream().filter(e -> e.entryId.equals(e2)).findFirst().orElseThrow();
        check(store.updateTts(FP, SelfTalkReuseStore.SOURCE_WELCOME, e2Ref, "失败语音", "zh_cn"),
                "覆写应置脏成功");
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e3, "26-03-01-00:00:00", "第三条"), 2);
        store.saveDirty();
        check(Files.exists(sub.resolve("entry-" + e1 + ".md")),
                "任一写入失败时不得删除淘汰旧文件");
        check(Files.readString(sub.resolve("entry-" + e2 + ".md")).equals(e2Before),
                "写失败的原文件应保持不变（不被序列化失败破坏）");
        check(Files.exists(sub.resolve("entry-" + e3 + ".md")), "其余条目应继续写盘");
        // 恢复：移除占位目录后重试全部成功
        Files.delete(sub.resolve("entry-" + e2 + ".md.tmp"));
        store.saveDirty();
        check(!Files.exists(sub.resolve("entry-" + e1 + ".md")), "恢复后应删除淘汰旧文件");
        check(Files.readString(sub.resolve("entry-" + e2 + ".md")).contains("失败语音"),
                "恢复后原文件应更新为新内容");
    }

    /** 6. 删除边界预期运行时故障转为单条失败，不向调用链抛出，其余条目继续处理 */
    private static void deleteRuntimeFailureIsolated(Path dir) throws IOException {
        Set<UUID> throwIds = new HashSet<>();
        Set<UUID> failIds = new HashSet<>();
        SelfTalkReuseStore store = newStore(dir, 1, failIds, throwIds);
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e1, "26-01-01-00:00:00", "第一条"), 1);
        store.saveDirty();
        store.append(FP, SelfTalkReuseStore.SOURCE_WELCOME, entry(e2, "26-02-01-00:00:00", "第二条"), 1);
        throwIds.add(e1);
        store.saveDirty();
        Path sub = dir.resolve("generated").resolve("welcome").resolve("v1-" + FP);
        check(Files.exists(sub.resolve("entry-" + e1 + ".md")),
                "删除抛出运行时故障应转为失败保留文件，不向调用链抛出");
        check(Files.exists(sub.resolve("entry-" + e2 + ".md")), "其余条目写盘不受影响");
        throwIds.clear();
        store.saveDirty();
        check(!Files.exists(sub.resolve("entry-" + e1 + ".md")), "恢复后删除重试应成功");
    }

    // ===== 辅助 =====

    /** 自检构造：容量固定为 capacity，删除操作为真实删除但跳过 failIds，throwIds 抛运行时故障 */
    private static SelfTalkReuseStore newStore(Path dir, int capacity,
                                               Set<UUID> failIds, Set<UUID> throwIds) {
        return new SelfTalkReuseStore(dir, () -> capacity, (sourceDir, id) -> {
            if (throwIds.contains(id)) {
                throw new IllegalStateException("injected delete failure for self-check");
            }
            if (failIds.contains(id)) {
                return false;
            }
            try {
                Files.deleteIfExists(sourceDir.resolve("entry-" + id + ".md"));
                return true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static SelfTalkReuseStore newStore(Path dir, int capacity, Set<UUID> failIds) {
        return newStore(dir, capacity, failIds, new HashSet<>());
    }

    private static SelfTalkReuseStore.PrebuiltEntry entry(UUID id, String createdAt, String chat) {
        return new SelfTalkReuseStore.PrebuiltEntry(id, createdAt, Map.of(), "zh_cn", chat,
                null, null, SelfTalkReuseStore.Origin.MACHINE);
    }

    /** 断言内存库恰好包含给定 id 集合 */
    private static void checkIds(SelfTalkReuseStore store, String message, UUID... expected) {
        Set<UUID> actual = new HashSet<>();
        for (SelfTalkReuseStore.PrebuiltEntry e : store.entries(FP, SelfTalkReuseStore.SOURCE_WELCOME)) {
            actual.add(e.entryId);
        }
        check(actual.size() == expected.length && actual.containsAll(Set.of(expected)),
                message + "，实际 " + actual);
    }

    /** 反射注入只读闩锁（模拟目录无法枚举的坏档状态） */
    private static void markUnreadable(SelfTalkReuseStore store) throws Exception {
        java.lang.reflect.Field fingerprintsField = SelfTalkReuseStore.class.getDeclaredField("fingerprints");
        fingerprintsField.setAccessible(true);
        Map<?, ?> fingerprints = (Map<?, ?>) fingerprintsField.get(store);
        Map<?, ?> sources = (Map<?, ?>) fingerprints.get(FP);
        Object sourceEntries = sources.get(SelfTalkReuseStore.SOURCE_WELCOME);
        java.lang.reflect.Field unreadable = sourceEntries.getClass().getDeclaredField("unreadable");
        unreadable.setAccessible(true);
        unreadable.setBoolean(sourceEntries, true);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("SelfTalkReuseStoreTest: " + message);
        }
    }
}
