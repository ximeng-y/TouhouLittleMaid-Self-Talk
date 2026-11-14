package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.maidmod.selftalk.OverheardChatSavedData;
import com.maidmod.selftalk.OverheardSummaryHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.net.http.HttpRequest;
import java.util.List;

/**
 * 实际摘要 callback 的偷听覆盖处理。
 * <p>
 * 元数据只属于这一次真实回调：由 Manager 的派发包装在 {@code LLMClient.chat} 调用点移交，
 * 不建全局 callback 表，也不恢复已发出的请求。
 * <p>
 * 成功判定只看原生结果：{@code completeHistorySummary} 返回 true（摘要非空、尾部快照一致、
 * 已按快照删除旧消息）才安排覆盖编号退出有效范围；返回 false、摘要空白早退与 {@code onFailure}
 * 都不退出。
 * <p>
 * 退出任务排在原 {@code runAfterSummary} 入队之前：恢复的玩家聊天看到的是更新后的有效范围。
 * 执行时再次核对清空令牌与 store／女仆绑定，清空或停服后的旧回调不会写进新数据。
 */
@Mixin(value = HistorySummaryCallback.class, remap = false)
public abstract class HistorySummaryCallbackMixin implements OverheardSummaryHost {

    @Unique
    private OverheardSummaryHost.Context maid_self_talk$overheardSummary;

    @Override
    public void maid_self_talk$setOverheardSummary(Context context) {
        this.maid_self_talk$overheardSummary = context;
    }

    @Override
    public OverheardSummaryHost.Context maid_self_talk$overheardSummary() {
        return this.maid_self_talk$overheardSummary;
    }

    @WrapOperation(method = "onSuccess", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/summary/HistorySummaryManager;completeHistorySummary(Ljava/lang/String;Ljava/util/List;)Z"))
    private boolean maid_self_talk$retireAfterAcceptedSummary(HistorySummaryManager manager, String summary,
                                                               List<LLMMessage> snapshot,
                                                               Operation<Boolean> original) {
        boolean accepted = original.call(manager, summary, snapshot);
        if (accepted) {
            scheduleRetire();
        }
        return accepted;
    }

    /** 原生接受成功：安排覆盖编号在当前主线程任务之后退出有效范围（先于 runAfterSummary 执行） */
    @Unique
    private void scheduleRetire() {
        OverheardSummaryHost.Context context = this.maid_self_talk$overheardSummary;
        if (context == null) {
            return;
        }
        EntityMaid maid = ((HistorySummaryCallback) (Object) this).getMaid();
        if (maid == null || maid.level().getServer() == null) {
            return;
        }
        maid.level().getServer().execute(() -> {
            if (!context.token().isValid() || context.store() == null || context.coveredIds().isEmpty()) {
                return;
            }
            context.store().retireEffective(context.maidUuid(), context.coveredIds());
            // 退出自动携带是持久变更：与其他写入点一样立即比对并置脏，
            // 不依赖之后恰好还有别的偷听写入把它顺带落盘
            OverheardChatSavedData data = OverheardChatSavedData.get(maid.level().getServer());
            if (data != null) {
                data.markDirtyIfChanged();
            }
        });
    }

    /** 失败路径：不退出有效原文，只清理本回调的元数据 */
    @Inject(method = "onFailure", remap = false, at = @At("HEAD"))
    private void maid_self_talk$clearOnFailure(@Nullable HttpRequest request, Throwable throwable, int errorCode,
                                               CallbackInfo ci) {
        this.maid_self_talk$overheardSummary = null;
    }

    /** 成功路径结束：元数据随之释放（覆盖编号已在原生接受时安排退出） */
    @Inject(method = "onSuccess", remap = false, at = @At("RETURN"))
    private void maid_self_talk$clearAfterSuccess(ResponseChat response, CallbackInfo ci) {
        this.maid_self_talk$overheardSummary = null;
    }
}
