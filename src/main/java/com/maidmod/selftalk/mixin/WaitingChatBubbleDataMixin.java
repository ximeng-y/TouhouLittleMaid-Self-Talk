package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.WaitingChatBubbleData;
import com.maidmod.selftalk.SelfTalkWaitingBubbleData;
import com.maidmod.selftalk.SelfTalkWaitingBubbles;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 为原生等待气泡附加「自话/互聊来源」标记（issue #78）。
 * <p>
 * 宿主为 {@link WaitingChatBubbleData}：新增 {@code @Unique} 布尔字段（默认未标记），
 * 并在实例方法 {@code id()} 的 HEAD 注入——已标记时返回 {@code maid_self_talk:autonomous_waiting}，
 * 否则保留原返回值。不修改 {@link WaitingChatBubbleData#ID} 静态常量、构造器、字段、
 * {@code getRenderer()} 或原生 serializer。
 */
@Mixin(value = WaitingChatBubbleData.class, remap = false)
public abstract class WaitingChatBubbleDataMixin implements SelfTalkWaitingBubbleData {

    @Unique
    private boolean maidSelfTalk$autonomousWaiting = false;

    @Override
    public boolean isAutonomousWaiting() {
        return this.maidSelfTalk$autonomousWaiting;
    }

    @Override
    public void setAutonomousWaiting(boolean value) {
        this.maidSelfTalk$autonomousWaiting = value;
    }

    /**
     * 已标记的等待气泡在实体数据同步与渲染时以专用类型 ID 出现（HEAD 注入，未标记保持原值）。
     */
    @Inject(method = "id", at = @At("HEAD"), cancellable = true)
    private void maidSelfTalk$autonomousWaitingId(CallbackInfoReturnable<ResourceLocation> cir) {
        if (this.maidSelfTalk$autonomousWaiting) {
            cir.setReturnValue(SelfTalkWaitingBubbles.AUTONOMOUS_WAITING_ID);
        }
    }
}