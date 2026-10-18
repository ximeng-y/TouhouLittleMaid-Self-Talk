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

    // ===== 断言工具 =====

    private static void check(String name, boolean condition) {
        checks++;
        if (!condition) {
            throw new AssertionError("FAILED: " + name);
        }
    }
}
