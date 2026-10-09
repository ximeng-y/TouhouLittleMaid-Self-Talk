package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.util.CappedQueue;
import com.google.common.collect.Lists;
import com.maidmod.selftalk.AutonomousChatHistory;
import com.maidmod.selftalk.AutonomousChatHistoryHost;
import com.maidmod.selftalk.AutonomousChatHistoryMigration;
import com.maidmod.selftalk.MaidSelfTalkService;
import com.maidmod.selftalk.SelfTalkProvenance;
import com.maidmod.selftalk.SelfTalkProvenanceHost;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * TLM 聊天数据上的两类绑定（全部 remap=false）：
 * <ul>
 *   <li><b>独立聊天档案</b>（本 mod 自有的自话／欢迎语／互聊存储）的宿主与顺序元数据；</li>
 *   <li><b>自话指纹</b>（段标签来源判定）的生命周期——存档、读档与历史清空。</li>
 * </ul>
 * <p>
 * 档案挂载：为每个 {@code MaidAIChatData} 实例持有一份 {@link AutonomousChatHistory}
 * （双线统一，不新增 NeoForge 附件或 Forge 实体字段）。数据随该女仆 AI 数据写入世界存档，
 * 不放静态状态表——静态表随实体卸载清空，而档案必须跨卸载与重启保留。
 * <p>
 * 顺序元数据：在四个 {@code add*History} 方法调用 {@code CappedQueue.add} 的位置重定向，
 * 捕获<b>实际传入的消息对象</b>，在同一把每实例锁内完成「分配顺序号 + 实际入队 + 旁路登记」。
 * 不修改消息正文/角色/工具字段，也不使用 {@code peekFirst()} 猜刚插入的是哪条消息
 * （响应线程与主线程交错时猜法必然出错）。
 * <p>
 * 档案的 NBT：{@code writeToTag} 内重定向 {@code Lists.newArrayList(history.getDeque())}，
 * 拿到 TLM <b>本次实际序列化的那一份快照</b>，顺序元数据与之逐位对应——
 * 绝不分别读取两次可变队列再假设下标一致。{@code readFromTag} 在 RETURN 处读取已 settle 的队列，
 * 按同一序列位置重新绑定顺序号。
 * <p>
 * 客户端支持：原版开屏同步（同步 AI 数据包）同样走这两个方法，因此档案 tag 的读写必须客户端可用；
 * 指纹属于服务端运行状态（{@link SelfTalkProvenanceHost}，客户端实体不携带），
 * 其存档/读档/清空一律在服务端分支内，客户端跳过。
 */
@Mixin(MaidAIChatData.class)
public abstract class MaidAIChatDataMixin implements AutonomousChatHistoryHost {

    /** 每实例一份的独立档案（惰性创建：读写任一侧首次访问时建立） */
    @Unique
    private AutonomousChatHistory maid_self_talk$history;

    @Override
    public AutonomousChatHistory maid_self_talk$autonomousHistory() {
        if (this.maid_self_talk$history == null) {
            this.maid_self_talk$history = new AutonomousChatHistory(((MaidAIChatData) (Object) this).getMaid());
        }
        return this.maid_self_talk$history;
    }

    // ===== 顺序元数据：四个 add*History 的实际入队点 =====

    @Redirect(method = "addUserHistory(Ljava/lang/String;)V", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/util/CappedQueue;add(Ljava/lang/Object;)V"))
    private void maid_self_talk$orderUserHistory(CappedQueue<LLMMessage> queue, Object element) {
        maid_self_talk$orderEnqueue(queue, element);
    }

    /**
     * 普通 assistant 回复（不带工具）入队点。
     */
    @Redirect(method = "addAssistantHistory(Ljava/lang/String;)V", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/util/CappedQueue;add(Ljava/lang/Object;)V"))
    private void maid_self_talk$orderAssistantHistory(CappedQueue<LLMMessage> queue, Object element) {
        maid_self_talk$orderEnqueue(queue, element);
    }

    /**
     * 带工具调用的 assistant 入队点（玩家 chat 的工具过程仍会走这里，需要顺序号）。
     * <p>
     * 选择器写全描述符：{@code addAssistantHistory} 有重载，只给名字会让注入目标有歧义。
     */
    @Redirect(method = "addAssistantHistory(Ljava/lang/String;Ljava/util/List;)V", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/util/CappedQueue;add(Ljava/lang/Object;)V"))
    private void maid_self_talk$orderAssistantToolCallHistory(CappedQueue<LLMMessage> queue, Object element) {
        maid_self_talk$orderEnqueue(queue, element);
    }

    @Redirect(method = "addToolHistory(Ljava/lang/String;Ljava/lang/String;)V", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/util/CappedQueue;add(Ljava/lang/Object;)V"))
    private void maid_self_talk$orderToolHistory(CappedQueue<LLMMessage> queue, Object element) {
        maid_self_talk$orderEnqueue(queue, element);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void maid_self_talk$orderEnqueue(CappedQueue<LLMMessage> queue, Object element) {
        if (element instanceof LLMMessage message) {
            maid_self_talk$autonomousHistory().trackTlmEnqueue(queue, message);
            return;
        }
        // 未知元素类型（TLM 未来改动的兜底）：原样入队，不影响其原有行为
        @SuppressWarnings("rawtypes")
        CappedQueue raw = queue;
        raw.add(element);
    }

    // ===== 档案 NBT =====

    /**
     * 捕获 TLM 本次实际序列化的历史快照：重定向 {@code Lists.newArrayList(history.getDeque())}，
     * 先记下这份列表，再原样交给 TLM 继续编码。
     * <p>
     * 顺序元数据必须与这份快照逐位对应——若改成在 writeToTag 返回后再读一次队列，
     * 两次读取之间可能发生容量淘汰或新消息入队，下标就对不上了。
     */
    @Redirect(method = "writeToTag", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/google/common/collect/Lists;newArrayList(Ljava/lang/Iterable;)Ljava/util/ArrayList;"))
    private ArrayList<LLMMessage> maid_self_talk$captureSerializedSnapshot(Iterable<LLMMessage> deque) {
        ArrayList<LLMMessage> snapshot = Lists.newArrayList(deque);
        maid_self_talk$pendingSnapshot = snapshot;
        return snapshot;
    }

    /** TLM 本次写出的历史快照（由上面的重定向在编码前填入，writeToTag 返回后即被使用并清空） */
    @Unique
    private List<LLMMessage> maid_self_talk$pendingSnapshot;

    /**
     * 服务端 AI 数据保存／同步前的最后一次迁移机会。
     * <p>
     * 必须在 TLM 序列化历史<b>之前</b>执行：迁移会把旧自话移出 TLM 队列，
     * 先序列化再迁移会让存档里同时留下「TLM 历史中的旧自话」与「档案中的同一条」，
     * 下次读档即出现重复展示。非主线程时迁移自行延后（本次照常写出，迁移完成标记未落位，
     * 下次保存再收敛——两条路径都不会产生重复记录）。
     */
    @Inject(method = "writeToTag", remap = false, at = @At("HEAD"))
    private void maid_self_talk$migrateBeforeWrite(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        AutonomousChatHistoryMigration.ensureMigratedForSave((MaidAIChatData) (Object) this);
    }

    @Inject(method = "writeToTag", remap = false, at = @At("RETURN"))
    private void maid_self_talk$writeAutonomousHistory(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        MaidAIChatData self = (MaidAIChatData) (Object) this;
        // 快照为空说明本次历史为空（TLM 未写出历史），顺序元数据取空表即可；
        // 档案本体与顺序号分配器仍需随存档落盘，否则清空后的隐藏边界与后续顺序会丢
        List<LLMMessage> snapshot = this.maid_self_talk$pendingSnapshot;
        this.maid_self_talk$pendingSnapshot = null;
        AutonomousChatHistoryHost.write(self, tag,
                snapshot == null ? List.of() : snapshot);
    }

    /**
     * 读档：TLM 自身的历史解析在 RETURN 前已完成，此时队列已 settle，
     * 按同一序列位置重新绑定顺序号（相同正文、相同 tick 的不同消息不得合并）。
     */
    @Inject(method = "readFromTag", remap = false, at = @At("RETURN"))
    private void maid_self_talk$readAutonomousHistory(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        MaidAIChatData self = (MaidAIChatData) (Object) this;
        // descendingIterator 给出的是旧到新的顺序（队列头部最新），与序列化时一致
        List<LLMMessage> dequeInOrder = new ArrayList<>();
        self.getHistory().getDeque().descendingIterator().forEachRemaining(dequeInOrder::add);
        AutonomousChatHistoryHost.read(self, tag, dequeInOrder);
    }

    // ===== 历史清空（TLM 清理记忆） =====

    /**
     * 清空前：完成必要的旧数据迁移，避免旧自话尚未归档就被原版删除。
     */
    @Inject(method = "clearAllChatMemory", remap = false, at = @At("HEAD"))
    private void maid_self_talk$migrateBeforeClear(CallbackInfo ci) {
        AutonomousChatHistoryMigration.ensureMigratedOnServerThread(
                ((MaidAIChatData) (Object) this).getMaid());
    }

    /**
     * 清空后：TLM 已清掉普通历史、摘要与 token 用量；此处同步清空本 mod 的独立状态。
     * <ul>
     *   <li>有效自话上下文清空（不再参与模型请求）；</li>
     *   <li>展示隐藏边界推进到当前末尾（旧记录立即不展示，但仍在档案里，不重置顺序号分配器）；</li>
     *   <li>旁路顺序元数据按已清空的队列剪枝（队列已空，死条目一并清掉）；</li>
     *   <li>服务端额外清理自话指纹与检索缓存、作废在途请求；客户端实体不携带这些运行状态，
     *       只做上面几项。</li>
     * </ul>
     * 隐藏边界随世界存档保留：重新打开界面、重新登录、重启服务器后仍然有效，
     * 而清空之后产生的新记录顺序号更大，正常显示。
     */
    @Inject(method = "clearAllChatMemory", remap = false, at = @At("TAIL"))
    private void maid_self_talk$clearProvenance(CallbackInfo ci) {
        MaidAIChatData self = (MaidAIChatData) (Object) this;
        EntityMaid maid = self.getMaid();
        AutonomousChatHistory history = maid_self_talk$autonomousHistory();
        history.clearValidContext();
        history.advanceHiddenBoundary();
        history.pruneOrderMetadata(self.getHistory().getDeque());
        if (maid == null || maid.level().isClientSide()) {
            // 客户端：原版按钮已清空本地显示列表；这里同步档案侧的隐藏边界与有效上下文镜像
            return;
        }
        SelfTalkProvenance.clearAll(maid);
        // 服务端：推进清空世代并作废清空前已开始的本 mod 请求（含在途规划与已派发的正式请求、
        // 互聊链上对方对本女仆的续接）。作废后迟到结果只清自己的资源，不写档案/窗口/事件/广播。
        // 这里只处理本 mod 的请求，不扩展为全面重写 TLM 普通玩家请求的取消机制
        MaidSelfTalkService.invalidateRequestsOnClear(maid);
    }

    // ===== 自话指纹 NBT（服务端运行状态） =====

    private static final String FINGERPRINTS_TAG = "maid_self_talk:self_talk_fingerprints";
    private static final String LEGACY_FINGERPRINTS_TAG = "maid_self_talk:legacy_fingerprints";
    private static final String LEGACY_INIT_TAG = "maid_self_talk:legacy_init";

    @Inject(method = "writeToTag", remap = false, at = @At("RETURN"))
    private void maid_self_talk$writeProvenance(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        MaidAIChatData self = (MaidAIChatData) (Object) this;
        EntityMaid maid = self.getMaid();
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        SelfTalkProvenanceHost host = (SelfTalkProvenanceHost) maid;
        writeFingerprints(tag, FINGERPRINTS_TAG, host.maid_self_talk$selfFingerprints());
        writeFingerprints(tag, LEGACY_FINGERPRINTS_TAG, host.maid_self_talk$legacyFingerprints());
        if (host.maid_self_talk$legacyInitialized()) {
            tag.putBoolean(LEGACY_INIT_TAG, true);
        }
    }

    @Inject(method = "readFromTag", remap = false, at = @At("RETURN"))
    private void maid_self_talk$readProvenance(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        MaidAIChatData self = (MaidAIChatData) (Object) this;
        EntityMaid maid = self.getMaid();
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        SelfTalkProvenanceHost host = (SelfTalkProvenanceHost) maid;
        if (tag.contains(FINGERPRINTS_TAG)) {
            host.maid_self_talk$selfFingerprints().clear();
            readFingerprints(tag, FINGERPRINTS_TAG, host.maid_self_talk$selfFingerprints());
        }
        if (tag.contains(LEGACY_FINGERPRINTS_TAG)) {
            host.maid_self_talk$legacyFingerprints().clear();
            readFingerprints(tag, LEGACY_FINGERPRINTS_TAG, host.maid_self_talk$legacyFingerprints());
        }
        if (tag.contains(LEGACY_INIT_TAG)) {
            host.maid_self_talk$setLegacyInitialized(tag.getBoolean(LEGACY_INIT_TAG));
        }
    }

    private static void writeFingerprints(CompoundTag tag, String key, Set<String> fingerprints) {
        if (fingerprints.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (String fingerprint : fingerprints) {
            list.add(StringTag.valueOf(fingerprint));
        }
        tag.put(key, list);
    }

    private static void readFingerprints(CompoundTag tag, String key, Set<String> target) {
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (Tag element : list) {
            target.add(element.getAsString());
        }
    }
}
