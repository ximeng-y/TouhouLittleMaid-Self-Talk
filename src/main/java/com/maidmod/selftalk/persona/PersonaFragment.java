package com.maidmod.selftalk.persona;

/**
 * 四栏人设中可召回的一段完整片段（背景或爱好）。
 * <p>
 * 「完整」有明确含义：片段就是四栏原文里的一句话（按换行分段、段内按句切分），
 * 保留原文的句子与标点，<b>不</b>做模型改写、<b>不</b>按长度截断——
 * 存储与检索都不删减玩家写下的内容。检索时只做排序与挑选，不改文本。
 *
 * @param category 来源分类（背景／爱好，两类永不合并）
 * @param text     片段原文（已去除首尾空白，句内内容与标点原样保留）
 * @param order    在整份人设中的原始顺序号（背景在前、爱好在后，各自保持原文顺序）：
 *                 既是输出顺序的依据，也是同分时的确定性 tie-break
 */
public record PersonaFragment(Category category, String text, int order) {

    /** 可召回的动态人设分类：只有背景与爱好参与召回（身份、性格每轮固定注入，不参与检索） */
    public enum Category {
        BACKGROUND,
        HOBBIES
    }
}
