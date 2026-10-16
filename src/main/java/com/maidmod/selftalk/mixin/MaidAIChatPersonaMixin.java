package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaHost;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 女仆人设配置（模式 + 四栏内容）的宿主挂载与 NBT 读写。
 * <p>
 * 挂载：为每个 {@code MaidAIChatData} 实例持有一份 {@link MaidPersonaConfig}，
 * 初始为原版模式 + 空四栏。配置随该女仆 AI 数据写入世界存档，因此重开界面、重新加载女仆、
 * 重启服务端后都能恢复；不放进静态 Map、玩家设置或聊天历史对象。
 * <p>
 * NBT：在 TLM {@code writeToTag/readFromTag} 的 RETURN 处独立读写
 * {@code MaidSelfTalkPersonaMode} 字符串字段与 {@code MaidSelfTalkPersona} 复合标签，
 * 不触碰 TLM 自有八字段与历史 NBT。读档整份替换内存值——字段缺失即恢复默认配置，
 * 绝不因字段缺失而残留上一次的内容；读档不做 trim/截断/清洗。
 * <p>
 * 客户端支持：原版开屏同步（SyncMaidAIDataPacket）同样走这两个方法，因此配置的读写必须客户端可用；
 * 客户端据此在编辑页打开时读到已同步配置，无需另建查询包。
 * <p>
 * 本类只负责「持有 + 存取」，不参与任何聊天请求的构造。
 */
@Mixin(MaidAIChatData.class)
public abstract class MaidAIChatPersonaMixin implements MaidPersonaHost {

    /** 每实例一份的完整配置（初始为默认配置；读档时整份替换） */
    @Unique
    private MaidPersonaConfig maid_self_talk$personaConfig = MaidPersonaConfig.DEFAULT;

    @Override
    public MaidPersonaConfig maid_self_talk$personaConfig() {
        if (this.maid_self_talk$personaConfig == null) {
            this.maid_self_talk$personaConfig = MaidPersonaConfig.DEFAULT;
        }
        return this.maid_self_talk$personaConfig;
    }

    @Override
    public void maid_self_talk$setPersonaConfig(MaidPersonaConfig config) {
        this.maid_self_talk$personaConfig = config == null ? MaidPersonaConfig.DEFAULT : config;
    }

    @Inject(method = "writeToTag", remap = false, at = @At("RETURN"))
    private void maid_self_talk$writePersonaConfig(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        MaidPersonaHost.write((MaidAIChatData) (Object) this, tag);
    }

    @Inject(method = "readFromTag", remap = false, at = @At("RETURN"))
    private void maid_self_talk$readPersonaConfig(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        MaidPersonaHost.read((MaidAIChatData) (Object) this, tag);
    }
}
