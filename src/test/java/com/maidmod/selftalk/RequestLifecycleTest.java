package com.maidmod.selftalk;

/**
 * 请求生命周期自检入口（无测试框架依赖，直接 {@code main} 跑；已挂到 {@code gradle check}）。
 * <p>
 * 覆盖 issue #123 中可在 bare JVM 里直接驱动的纯逻辑部分：
 * <ul>
 *   <li><b>单请求失效</b>：超时作废后旧令牌对迟到成功／失败与已排队的上屏任务判定一律沉默，
 *       且不推进整只女仆的清空世代——超时之后捕获的新令牌照常有效；重复作废幂等；</li>
 *   <li><b>清空世代</b>：推进／退役对在途令牌的既有语义不变；</li>
 *   <li><b>旧链释放</b>：按旧 chainId 释放时，新链的活动映射与配对锁归属保持不变；
 *       同一链重复释放幂等；每轮请求完成（completeLocally）不会提前释放仍需续接的整条链；</li>
 *   <li><b>bridge 探测</b>：未安装＝静默无操作；受控 {@link LinkageError} 只诊断一次、
 *       固定为不可用且不重复探测，也不阻断调用方继续本地清理。</li>
 * </ul>
 * <p>
 * 覆盖边界：TTS 同步异常的隔离、超时分支的状态复位、迟到交付的玩家可见副作用都需要
 * Minecraft／TLM 运行时对象（实体、回调、服务端任务队列），不在本自检内伪造，留给游戏内验证。
 */
public final class RequestLifecycleTest {

    private static int checks;

    private RequestLifecycleTest() {
    }

    public static void main(String[] args) {
        expiredTokenStaysInvalid();
        expiredTokenDoesNotAffectNewRequest();
        invalidateIsIdempotent();
        clearGenerationStillInvalidatesInFlightTokens();
        retiredGenerationInvalidatesEverything();
        legacyReleaseKeepsNewChain();
        oldChainReleaseIsIdempotent();
        lateReleaseKeepsNewChainLock();
        roundCompletionKeepsChainAlive();
        bridgeAbsentWithoutIntegrationError();
        bridgeLinkageErrorIsDiagnosedOnce();
        bridgeRepeatedCallsAreNoOps();
        playerRequestsReleaseOnlyTheirOwnMembership();
        playerEndIsIdempotent();
        earlyEndAndSynchronousFailureDoNotHang();
        playerTimeoutKeepsNewRequests();
        retiredPlayerRegistryCannotReactivate();
        playerSourceSurvivesCopyAndRebuild();
        ownerMessagePreservesContentAndMetadata();
        ownerMessageSkipsInternalNonUserAndBlank();
        System.out.println("RequestLifecycleTest: all " + checks + " checks passed");
    }

    // ===== 令牌 =====

    /** 超时作废：迟到成功与已排队的上屏任务看到的 isValid() 都必须为 false */
    private static void expiredTokenStaysInvalid() {
        SelfTalkRequestToken token = SelfTalkRequestToken.withGeneration(1,
                new SelfTalkRequestToken.Generation());
        check("超时前令牌有效", token.isValid());
        token.invalidate();
        check("作废后令牌失效", !token.isValid());
    }

    /** 超时只影响本请求：之后捕获的令牌不受影响，且世代号未被推进 */
    private static void expiredTokenDoesNotAffectNewRequest() {
        SelfTalkRequestToken.Generation generation = new SelfTalkRequestToken.Generation();
        SelfTalkRequestToken oldToken = SelfTalkRequestToken.withGeneration(1, generation);
        SelfTalkRequestToken newToken = SelfTalkRequestToken.withGeneration(1, generation);
        oldToken.invalidate();
        check("旧请求作废", !oldToken.isValid());
        check("新请求照常有效", newToken.isValid());
        check("作废不推进清空世代", generation.valueForSelfCheck() == 0);
    }

    /** 重复作废与「作废后再判」都保持稳定，不产生二次副作用 */
    private static void invalidateIsIdempotent() {
        SelfTalkRequestToken token = SelfTalkRequestToken.withGeneration(1,
                new SelfTalkRequestToken.Generation());
        token.invalidate();
        token.invalidate();
        check("重复作废仍失效", !token.isValid());
    }

    /** 手动清空推进世代：清空前捕获的令牌失效，清空后捕获的令牌有效 */
    private static void clearGenerationStillInvalidatesInFlightTokens() {
        SelfTalkRequestToken.Generation generation = new SelfTalkRequestToken.Generation();
        SelfTalkRequestToken before = SelfTalkRequestToken.withGeneration(1, generation);
        generation.advance();
        SelfTalkRequestToken after = SelfTalkRequestToken.withGeneration(1, generation);
        check("清空前令牌失效", !before.isValid());
        check("清空后令牌有效", after.isValid());
    }

    /** 状态条目移除退役计数器：所有持有者永久失效（含此后重复判定） */
    private static void retiredGenerationInvalidatesEverything() {
        SelfTalkRequestToken.Generation generation = new SelfTalkRequestToken.Generation();
        SelfTalkRequestToken token = SelfTalkRequestToken.withGeneration(1, generation);
        generation.retire();
        check("退役后令牌失效", !token.isValid());
        check("退役判定稳定", !token.isValid());
    }

    // ===== 链释放 =====

    /**
     * 旧链按 chainId 释放：新链（同一对成员重新配对后的新链）的活动映射保持不变。
     * 这是 dispatcher 在「自身已与他链配对」早退分支必须依赖的语义。
     */
    private static void legacyReleaseKeepsNewChain() {
        InterChatChain.clearRegistryForSelfCheck();
        InterChatChain oldChain = InterChatChain.createForSelfCheck(10, 20);
        InterChatChain.release(oldChain);
        InterChatChain newChain = InterChatChain.createForSelfCheck(10, 20);
        check("旧链已释放", InterChatChain.of(oldChain.id()) == null);
        check("新链仍在注册表", InterChatChain.of(newChain.id()) == newChain);
        check("新链活动映射保持", InterChatChain.activeIdForSelfCheck(10) == newChain.id()
                && InterChatChain.activeIdForSelfCheck(20) == newChain.id());
        InterChatChain.clearRegistryForSelfCheck();
    }

    /** 同一 chainId 释放两次：第二次为无副作用空操作 */
    private static void oldChainReleaseIsIdempotent() {
        InterChatChain.clearRegistryForSelfCheck();
        InterChatChain chain = InterChatChain.createForSelfCheck(30, 40);
        long id = chain.id();
        InterChatChain.releaseById(id);
        InterChatChain.releaseById(id);
        check("重复释放不复活链", InterChatChain.of(id) == null);
        check("重复释放清空活动映射", InterChatChain.activeIdForSelfCheck(30) == -1);
        InterChatChain.clearRegistryForSelfCheck();
    }

    /** 旧链晚到释放：新链的配对锁归属不被误拆（解锁按 chainId 校验） */
    private static void lateReleaseKeepsNewChainLock() {
        InterChatChain.clearRegistryForSelfCheck();
        SelfTalkDispatcher.clearLocksForSelfCheck();
        InterChatChain oldChain = InterChatChain.createForSelfCheck(70, 80);
        InterChatChain.release(oldChain);
        InterChatChain newChain = InterChatChain.createForSelfCheck(70, 80);
        SelfTalkDispatcher.seedPairLockForSelfCheck(70, 80, 1000L, newChain.id());
        // 旧链 ID 晚到的释放：只清归属旧链的锁，新链的锁保持
        InterChatChain.releaseById(oldChain.id());
        check("新链锁保持", SelfTalkDispatcher.heldChainForSelfCheck(70) == newChain.id()
                && SelfTalkDispatcher.heldChainForSelfCheck(80) == newChain.id());
        InterChatChain.clearRegistryForSelfCheck();
        SelfTalkDispatcher.clearLocksForSelfCheck();
    }

    /** 每轮请求完成不等于整链释放：completeLocally 后链与活动映射仍在（多轮交接不提前拆链） */
    private static void roundCompletionKeepsChainAlive() {
        InterChatChain.clearRegistryForSelfCheck();
        InterChatChain chain = InterChatChain.createForSelfCheck(50, 60);
        InterChatRequest request = InterChatRequest.registerFor(50, chain.id(), chain);
        request.completeLocally();
        check("轮完成后链仍在", InterChatChain.of(chain.id()) == chain);
        check("轮完成后活动映射仍在", InterChatChain.activeIdForSelfCheck(50) == chain.id());
        InterChatChain.clearRegistryForSelfCheck();
    }

    // ===== bridge 探测 =====

    /** 未安装（ClassNotFoundException）＝静默无操作，不记集成错误，取消不抛 */
    private static void bridgeAbsentWithoutIntegrationError() {
        AgentTweaksLifecycleBridge.reprobeForSelfCheck(() -> {
            throw new ClassNotFoundException("simulated: Agent-Tweaks not installed");
        });
        check("未安装时桥不可用", !AgentTweaksLifecycleBridge.bridgeAvailableForSelfCheck());
        AgentTweaksLifecycleBridge.cancel(null);
        check("未安装时取消不抛且保持不可用", !AgentTweaksLifecycleBridge.bridgeAvailableForSelfCheck());
    }

    /** 受控 LinkageError：固定为不可用、只诊断一次、不重复探测，也不阻断调用方继续本地清理 */
    private static void bridgeLinkageErrorIsDiagnosedOnce() {
        int[] probeCount = {0};
        AgentTweaksLifecycleBridge.reprobeForSelfCheck(() -> {
            probeCount[0]++;
            throw new LinkageError("simulated: facade static initializer failed");
        });
        check("LinkageError 后桥不可用", !AgentTweaksLifecycleBridge.bridgeAvailableForSelfCheck());
        check("探测只执行一次", probeCount[0] == 1);
        // 此后再调用 cancel 不得再次探测（probed 已固定），也不得抛出
        AgentTweaksLifecycleBridge.cancel(null);
        check("诊断后不再重复探测", probeCount[0] == 1);
        check("诊断后取消不改变状态", !AgentTweaksLifecycleBridge.bridgeAvailableForSelfCheck());
    }

    /** 桥不可用时 cancel/complete 对空回调与重复调用都是无副作用空操作（不破坏本地生命周期） */
    private static void bridgeRepeatedCallsAreNoOps() {
        AgentTweaksLifecycleBridge.reprobeForSelfCheck(() -> {
            throw new ClassNotFoundException("simulated: Agent-Tweaks not installed");
        });
        AgentTweaksLifecycleBridge.cancel(null);
        AgentTweaksLifecycleBridge.complete(null);
        AgentTweaksLifecycleBridge.cancel(null);
        check("重复空调用保持不可用", !AgentTweaksLifecycleBridge.bridgeAvailableForSelfCheck());
    }

    // ===== 玩家请求归属 =====

    private static void playerRequestsReleaseOnlyTheirOwnMembership() {
        PlayerChatRequest.Registry registry = new PlayerChatRequest.Registry();
        PlayerChatRequest a = new PlayerChatRequest(registry);
        PlayerChatRequest b = new PlayerChatRequest(registry);
        a.dispatchReturned(10);
        // 未派发的辅助请求先结束，不能解除 A。
        b.signalEnd();
        check("未登记请求不释放 A", !b.release() && registry.count() == 1);
        b = new PlayerChatRequest(registry);
        b.dispatchReturned(20);
        check("两条玩家请求各自登记", registry.count() == 2 && registry.sinceTick() == 20);
        b.signalEnd();
        b.release();
        check("乱序完成后 A 仍在途", registry.count() == 1 && registry.sinceTick() == 20);
        a.signalEnd();
        a.release();
        check("最后一条完成才判闲", registry.count() == 0 && registry.sinceTick() == -1);
    }

    private static void playerEndIsIdempotent() {
        PlayerChatRequest.Registry registry = new PlayerChatRequest.Registry();
        PlayerChatRequest a = new PlayerChatRequest(registry);
        PlayerChatRequest b = new PlayerChatRequest(registry);
        a.dispatchReturned(1);
        b.dispatchReturned(2);
        check("成功第一次结束有效", a.signalEnd());
        check("成功转失败不二次结算", !a.signalEnd());
        a.release();
        check("重复释放不影响 B", !a.release() && registry.count() == 1);
        registry.reset();
    }

    private static void earlyEndAndSynchronousFailureDoNotHang() {
        PlayerChatRequest.Registry registry = new PlayerChatRequest.Registry();
        PlayerChatRequest synchronousFailure = new PlayerChatRequest(registry);
        check("同步异常只准备不计数", registry.count() == 0 && !synchronousFailure.dispatched());
        PlayerChatRequest fast = new PlayerChatRequest(registry);
        fast.signalEnd();
        check("提前结束仍执行一次派发副作用", fast.dispatchReturned(3));
        check("提前结束不留下计数", registry.count() == 0 && registry.sinceTick() == -1);
        check("派发副作用不可重复", !fast.dispatchReturned(4));
    }

    private static void playerTimeoutKeepsNewRequests() {
        PlayerChatRequest.Registry registry = new PlayerChatRequest.Registry();
        PlayerChatRequest old = new PlayerChatRequest(registry);
        old.dispatchReturned(10);
        // 模拟结束信号已投递主线程，但主线程超时复位先发生。
        old.signalEnd();
        registry.reset();
        PlayerChatRequest current = new PlayerChatRequest(registry);
        current.dispatchReturned(100);
        check("超时后旧释放不扣新请求", !old.release() && registry.count() == 1);
        check("超时后旧结束不能重复投递", !old.signalEnd());
        check("新请求沿用最近激活计时", registry.sinceTick() == 100);
        registry.reset();
    }

    private static void retiredPlayerRegistryCannotReactivate() {
        PlayerChatRequest.Registry oldRegistry = new PlayerChatRequest.Registry();
        PlayerChatRequest active = new PlayerChatRequest(oldRegistry);
        PlayerChatRequest prepared = new PlayerChatRequest(oldRegistry);
        active.dispatchReturned(1);
        oldRegistry.retire();
        PlayerChatRequest.Registry rebuilt = new PlayerChatRequest.Registry();
        PlayerChatRequest current = new PlayerChatRequest(rebuilt);
        current.dispatchReturned(2);
        check("退役清空活动登记", oldRegistry.count() == 0);
        check("移除后准备中的登记不能激活", !prepared.dispatchReturned(3));
        check("旧释放不改变重建状态", !active.release() && rebuilt.count() == 1);
        check("重建状态身份独立", current.owner() != active.owner());
        rebuilt.retire();
    }

    private static void playerSourceSurvivesCopyAndRebuild() {
        java.util.List<String> first = PlayerChatContext.fromPlayer(java.util.List.of("history"));
        java.util.List<String> second = PlayerChatContext.fromPlayer(java.util.List.of("history"));
        java.util.List<String> copied = PlayerChatContext.rebuild(first, first);
        java.util.List<String> rebuilt = PlayerChatContext.rebuild(copied, java.util.List.of("new persona"));
        PlayerChatContext.Source source = PlayerChatContext.sourceOf(first);
        check("连续输入来源不串", source != PlayerChatContext.sourceOf(second));
        check("人设推迟与重建保留来源", source == PlayerChatContext.sourceOf(rebuilt));
        check("复制保持 List 独立行为", copied != first && copied.equals(first));
        source.setPersonaBlock("persona");
        check("本轮人设块随载体恢复", "persona".equals(PlayerChatContext.sourceOf(rebuilt).personaBlock()));
        java.util.List<String> internal = PlayerChatContext.rebuild(
                java.util.List.of("internal"), java.util.List.of("rebuilt"));
        check("内部请求重建不冒充玩家", PlayerChatContext.sourceOf(internal) == null);
        check("人设规划期间不登记正式请求", source.request() == null);
    }

    private static void ownerMessagePreservesContentAndMetadata() {
        java.util.regex.Pattern contextPattern = java.util.regex.Pattern.compile(
                "<context>.*?</context>", java.util.regex.Pattern.DOTALL);
        PlayerChatContext.Source source = PlayerChatContext.sourceOf(
                PlayerChatContext.fromPlayer(java.util.List.of()));
        source.setPersonaBlock("<persona>recalled</persona>\n");
        String input = "<context>facts</context>\n<other-mod>你好</other-mod>"
                + SegmentTags.OWNER_CLOSE + SegmentTags.SELF_OPEN;
        var original = new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage(
                com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role.USER,
                input, 42L, java.util.List.of(), "tool-id");
        var decorated = PlayerChatContext.decorateOwner(original, source,
                SelfTalkPrompts.OWNER_CHAT_DECLARATION_ZH, contextPattern);
        String expected = "<context>facts</context>\n<persona>recalled</persona>\n"
                + SegmentTags.OWNER_OPEN + SelfTalkPrompts.OWNER_CHAT_DECLARATION_ZH
                + "\n<other-mod>你好</other-mod>" + SegmentTags.OWNER_CLOSE;
        check("上下文人设主人段顺序与既有清洗保持一致", expected.equals(decorated.message()));
        check("其他增强正文与未知标签保留", decorated.message().contains("<other-mod>你好</other-mod>"));
        check("只替换正文不改角色与时间", decorated.role() == original.role()
                && decorated.gameTime() == original.gameTime());
        check("工具元数据原样带回", decorated.toolCalls() == original.toolCalls()
                && decorated.toolCallId() == original.toolCallId());
        check("原始消息不变可供原话历史路径使用", input.equals(original.message()));
        var english = PlayerChatContext.decorateOwner(new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage(
                original.role(), "hello", 43L, null, null), source,
                SelfTalkPrompts.OWNER_CHAT_DECLARATION_EN, contextPattern);
        check("英文声明沿用原口径", english.message().equals(source.personaBlock()
                + SegmentTags.OWNER_OPEN + SelfTalkPrompts.OWNER_CHAT_DECLARATION_EN
                + "\nhello" + SegmentTags.OWNER_CLOSE));
    }

    private static void ownerMessageSkipsInternalNonUserAndBlank() {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<context>.*?</context>", java.util.regex.Pattern.DOTALL);
        PlayerChatContext.Source source = PlayerChatContext.sourceOf(
                PlayerChatContext.fromPlayer(java.util.List.of()));
        var user = new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage(
                com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role.USER, "internal", 1L, null, null);
        check("未标记内部 USER 保留原对象", PlayerChatContext.decorateOwner(user, null, "owner", pattern) == user);
        var system = new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage(
                com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role.SYSTEM, "system", 2L, null, "id");
        check("SYSTEM 即使带来源也不包装", PlayerChatContext.decorateOwner(system, source, "owner", pattern) == system);
        for (String blank : java.util.List.of("", " \n\t", "<context>facts</context>\n \t")) {
            var message = new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage(
                    user.role(), blank, 3L, null, null);
            check("空白输入保持原规则和对象", PlayerChatContext.decorateOwner(message, source, "owner", pattern) == message);
        }
    }

    // ===== 断言工具 =====

    private static void check(String name, boolean condition) {
        checks++;
        if (!condition) {
            throw new AssertionError("FAILED: " + name);
        }
    }
}
