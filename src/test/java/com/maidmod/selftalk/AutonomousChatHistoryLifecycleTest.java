package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.util.CappedQueue;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立档案的 TLM 顺序号旁路表生命周期自检（无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * 覆盖 issue #124 P1-3：旁路表只在 live 队列内保留引用，容量淘汰、压缩删除、手动清空后
 * 必须及时修剪；相同正文、相同 tick 的不同对象必须各自持有独立顺序号。
 * <p>
 * 只使用 {@link AutonomousChatHistory} 中不依赖 Minecraft 实体的入口（以 null 女仆构造），
 * 不触碰配置读取与 NBT 路径，因此可脱离游戏运行。已由 {@code gradle check} 真实执行，
 * 失败即构建失败。
 */
public final class AutonomousChatHistoryLifecycleTest {

    private static int checks = 0;
    private static int failures = 0;

    private AutonomousChatHistoryLifecycleTest() {
    }

    public static void main(String[] args) {
        testCapacityEviction();
        testCompressionPrune();
        testManualClear();
        testIdentityIsolation();
        System.out.printf("%n=== %d checks, %d failed ===%n", checks, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 容量淘汰：入队挤掉最旧消息后，被淘汰对象的旁路条目必须立即消失 */
    private static void testCapacityEviction() {
        AutonomousChatHistory history = new AutonomousChatHistory(null);
        CappedQueue<LLMMessage> queue = new CappedQueue<>(2);
        LLMMessage first = message("第一句", 1);
        LLMMessage second = message("第二句", 2);
        LLMMessage third = message("第三句", 3);

        history.trackTlmEnqueue(queue, first);
        history.trackTlmEnqueue(queue, second);
        check("淘汰前两条都已登记", history.seqOf(first) != null && history.seqOf(second) != null);
        check("淘汰前队列满额", queue.size() == 2);

        history.trackTlmEnqueue(queue, third);
        check("容量淘汰后旧消息旁路条目被修剪", history.seqOf(first) == null);
        check("仍在队列的消息保留条目",
                history.seqOf(second) != null && history.seqOf(third) != null);
        check("顺序号保持单调",
                history.seqOf(second) < history.seqOf(third));

        // 修剪不回收顺序号：再入队的新消息必须拿到更大的号
        LLMMessage fourth = message("第四句", 4);
        history.trackTlmEnqueue(queue, fourth);
        check("修剪后顺序号不回退", history.seqOf(fourth) > history.seqOf(third));
        check("再次淘汰只清最旧", history.seqOf(second) == null
                && history.seqOf(third) != null && history.seqOf(fourth) != null);
    }

    /** 压缩删除：TLM 从队尾删除旧消息后，显式修剪只清掉被删的那些 */
    private static void testCompressionPrune() {
        AutonomousChatHistory history = new AutonomousChatHistory(null);
        CappedQueue<LLMMessage> queue = new CappedQueue<>(8);
        List<LLMMessage> messages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            LLMMessage message = message("压缩前 " + i, i);
            messages.add(message);
            history.trackTlmEnqueue(queue, message);
        }
        // 模拟 completeHistorySummary 成功路径：从队尾（最旧端）逐条删除快照里的消息
        queue.getDeque().pollLast();
        queue.getDeque().pollLast();
        history.pruneOrderMetadata(queue.getDeque());
        check("压缩后旧消息条目被清", history.seqOf(messages.get(0)) == null
                && history.seqOf(messages.get(1)) == null);
        check("压缩后保留消息条目仍在", history.seqOf(messages.get(2)) != null
                && history.seqOf(messages.get(3)) != null);

        // 空队列修剪：条目全部清空且不抛异常
        queue.getDeque().clear();
        history.pruneOrderMetadata(queue.getDeque());
        check("空队列修剪清空全部条目", history.seqOf(messages.get(2)) == null
                && history.seqOf(messages.get(3)) == null);
    }

    /** 手动清空：队列清空后修剪，之后的新消息照常获得更大顺序号 */
    private static void testManualClear() {
        AutonomousChatHistory history = new AutonomousChatHistory(null);
        CappedQueue<LLMMessage> queue = new CappedQueue<>(8);
        LLMMessage before = message("清空前", 10);
        history.trackTlmEnqueue(queue, before);
        Long beforeSeq = history.seqOf(before);
        check("清空前条目存在", beforeSeq != null);

        queue.getDeque().clear();
        history.pruneOrderMetadata(queue.getDeque());
        check("清空后旧条目消失", history.seqOf(before) == null);

        LLMMessage after = message("清空后", 11);
        history.trackTlmEnqueue(queue, after);
        check("清空后新消息获得更大顺序号", history.seqOf(after) > beforeSeq);
    }

    /** 对象身份：相同正文、角色与 tick 的不同对象必须各自独立 */
    private static void testIdentityIsolation() {
        AutonomousChatHistory history = new AutonomousChatHistory(null);
        CappedQueue<LLMMessage> queue = new CappedQueue<>(8);
        // record 的 equals 只看字段：两条消息内容完全相同，必须按对象身份区分
        LLMMessage first = new LLMMessage(Role.USER, "重复的一句", 42, null, null);
        LLMMessage second = new LLMMessage(Role.USER, "重复的一句", 42, null, null);
        check("构造前提：两条消息 equals 相等", first.equals(second) && first != second);

        history.trackTlmEnqueue(queue, first);
        history.trackTlmEnqueue(queue, second);
        Long firstSeq = history.seqOf(first);
        Long secondSeq = history.seqOf(second);
        check("相同正文的不同对象各自登记", firstSeq != null && secondSeq != null);
        check("各自顺序号不同", !firstSeq.equals(secondSeq));

        // 淘汰其中一条后，只清对应条目，另一条不受影响
        queue.getDeque().remove(second);
        history.pruneOrderMetadata(queue.getDeque());
        check("身份隔离：被删对象条目消失", history.seqOf(second) == null);
        check("身份隔离：另一对象条目保留", firstSeq.equals(history.seqOf(first)));
    }

    private static LLMMessage message(String text, long gameTime) {
        return new LLMMessage(Role.USER, text, gameTime, null, null);
    }

    private static void check(String name, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}
