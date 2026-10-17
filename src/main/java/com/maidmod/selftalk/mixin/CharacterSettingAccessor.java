package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.CharacterSetting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 只读打开 TLM {@code CharacterSetting.rawSetting}（private final String）。
 * <p>
 * 用途：预制复用的身份指纹需要<b>原始模型设定</b>——{@code getSetting(maid, language)} 会经
 * PapiReplacer 替换占位符，同一女仆因语言或上下文不同产出不同文本，不能作为稳定身份输入；
 * {@code rawSetting} 是替换前的原始全文，同人同人设恒定。
 * <p>
 * 只读：调用方不得改写该字段。也不做反射 fallback——拿不到字段即 Mixin 未应用，
 * 属于必须暴露的启动期错误，不该被静默兜底掩盖。
 * <p>
 * {@code remap = false}：TLM 类在 prod 不混淆，字段名即 rawSetting。
 */
@Mixin(value = CharacterSetting.class, remap = false)
public interface CharacterSettingAccessor {

    @Accessor(value = "rawSetting", remap = false)
    String maid_self_talk$rawSetting();
}
