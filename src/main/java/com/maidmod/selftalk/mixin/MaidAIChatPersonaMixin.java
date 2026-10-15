package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 结构化人设草稿的宿主挂载与 NBT 读写。
 * <p>
 * 挂载：为每个 {@code MaidAIChatData} 实例持有一份 {@link MaidPersonaDraft}，初始四栏全空。
 * 草稿随该女仆 AI 数据写入世界存档，因此重开界面、重新加载女仆、重启服务端后都能恢复；
 * 不放进静态 Map、玩家设置或聊天历史对象。
 * <p>
 * NBT：在 TLM {@code writeToTag/readFromTag} 的 RETURN 处独立读写 {@code MaidSelfTalkPersona}
 * 复合标签，不触碰 TLM 自有八字段与历史 NBT。读档整份替换内存值——标签不存在即恢复空草稿，
 * 绝不因标签缺失而残留上一次的内容；读档不做 trim/截断/清洗。
 * <p>
 * 客户端支持：原版开屏同步（SyncMaidAIDataPacket）同样走这两个方法，因此草稿的读写必须客户端可用；
 * 客户端据此在编辑页打开时读到已同步草稿，无需另建查询包。
 * <p>
 * 本类只负责「持有 + 存取」，不参与任何聊天请求的构造。
 */
@Mixin(MaidAIChatData.class)
public abstract class MaidAIChatPersonaMixin implements MaidPersonaHost {

    /** 每实例一份的草稿（初始为空草稿；读档时整份替换） */
    @Unique
    private MaidPersonaDraft maid_self_talk$personaDraft = MaidPersonaDraft.EMPTY;

    @Override
    public MaidPersonaDraft maid_self_talk$personaDraft() {
        if (this.maid_self_talk$personaDraft == null) {
            this.maid_self_talk$personaDraft = MaidPersonaDraft.EMPTY;
        }
        return this.maid_self_talk$personaDraft;
    }

    @Override
    public void maid_self_talk$setPersonaDraft(MaidPersonaDraft draft) {
        this.maid_self_talk$personaDraft = draft == null ? MaidPersonaDraft.EMPTY : draft;
    }

    @Inject(method = "writeToTag", at = @At("RETURN"))
    private void maid_self_talk$writePersonaDraft(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        MaidPersonaHost.write((MaidAIChatData) (Object) this, tag);
    }

    @Inject(method = "readFromTag", at = @At("RETURN"))
    private void maid_self_talk$readPersonaDraft(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        MaidPersonaHost.read((MaidAIChatData) (Object) this, tag);
    }
}
