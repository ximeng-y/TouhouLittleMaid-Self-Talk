package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.GameContextRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.IMaidContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 只读打开 {@code GameContextRegister.CONTEXTS}（private static Map）。
 * <p>
 * 用途：按 key 判断某个上下文提供者是否<b>实际注册</b>，以及按 key 取回提供者自行调用
 * {@code label()} / {@code getValue(maid)}——上游只提供「按分类渲染」，无法逐项取值。
 * <p>
 * 只读：调用方不得删除、替换或重新注册条目（{@code init()} 会把该字段整体换成 ImmutableMap，
 * 任何写入尝试都会抛异常）；也不做反射 fallback、不按格式化字符串反向解析 key——
 * 拿不到表就是 Mixin 未应用，属于必须暴露的启动期错误，不该被静默兜底掩盖。
 * <p>
 * {@code remap = false}：TLM 类在 prod 不混淆，字段名即 CONTEXTS。
 */
@Mixin(value = GameContextRegister.class, remap = false)
public interface GameContextRegisterAccessor {

    @Accessor(value = "CONTEXTS", remap = false)
    static Map<String, IMaidContext> maid_self_talk$contexts() {
        throw new AssertionError("Mixin accessor not applied");
    }
}
