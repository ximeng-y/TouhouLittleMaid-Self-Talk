package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.SelfTalkProvenance;
import com.maidmod.selftalk.SelfTalkProvenanceHost;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * 自话指纹与 TLM 聊天数据的生命周期绑定（TLM 自有方法，全部 remap=false）：
 * <ul>
 *   <li>{@code clearAllChatMemory} TAIL：历史清空（清记忆）时同步清空指纹与 legacy 标记，
 *       避免"幽灵来源"残留。防御：TLM 客户端历史界面（HistoryAIChatScreen）也会调用本方法，
 *       客户端实体不携带本 mod 的运行状态，直接跳过；</li>
 *   <li>{@code writeToTag} RETURN：存档时把指纹/legacy 快照写进女仆顶层 NBT
 *       （与 TLM 的 MaidHistoryChat 等 key 平级，TLM 读取时忽略未知 key——卸载本 mod 后残留无害）；</li>
 *   <li>{@code readFromTag} RETURN：读档时恢复到实体字段（{@link SelfTalkProvenanceHost}）。
 *       同样跳过客户端：客户端实体零状态，与 clearAllChatMemory 防御一致。</li>
 * </ul>
 */
@Mixin(MaidAIChatData.class)
public abstract class MaidAIChatDataMixin {

    private static final String FINGERPRINTS_TAG = "maid_self_talk:self_talk_fingerprints";
    private static final String LEGACY_FINGERPRINTS_TAG = "maid_self_talk:legacy_fingerprints";
    private static final String LEGACY_INIT_TAG = "maid_self_talk:legacy_init";

    @Inject(method = "clearAllChatMemory", remap = false, at = @At("TAIL"))
    private void maid_self_talk$clearProvenance(CallbackInfo ci) {
        MaidAIChatData self = (MaidAIChatData) (Object) this;
        EntityMaid maid = self.getMaid();
        if (maid == null || maid.level().isClientSide()) {
            return;
        }
        SelfTalkProvenance.clearAll(maid);
    }

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
