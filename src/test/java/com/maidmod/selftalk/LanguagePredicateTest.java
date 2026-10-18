package com.maidmod.selftalk;

import java.util.List;

/**
 * 语言判定口径自检（issue #125 P3-1，无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * 只覆盖纯 Java 的语言标签处理：{@link SelfTalkContexts#sanitizeLanguage}、
 * {@link ContextLanguage}、{@link SelfTalkContexts#languageOf} 与输出语言指令
 * {@link SelfTalkContexts#languageInstruction}。
 * <p>
 * 刻意<b>不</b>引用 {@link SelfTalkPrompts}：在 NeoForge 线，该类会随 JDK21 的类校验触发
 * TLM 依赖类的加载错误（TLM/Minecraft 版本不匹配），使自检本身无法运行；
 * 规划模板的正反用例改由各段模板常量随构建验证覆盖。
 * <p>
 * 生产代码各段（自定义 Prompt、Tool 策略、历史说明、人设召回、规划触发、玩家 chat 声明）
 * 已全部改走 {@code SelfTalkContexts.languageOf} / {@link ContextLanguage#of}，
 * 本类验证的即是它们共用的那一套判定。
 */
public final class LanguagePredicateTest {

    private static int checks = 0;

    private LanguagePredicateTest() {
    }

    public static void main(String[] args) {
        sanitizedTagsKeptVerbatim();
        caseVariantsSelectSameLanguage();
        instructionAgreesWithTemplateLanguage();
        illegalAndMissingKeepExistingFallback();
        System.out.println("LanguagePredicateTest passed: " + checks + " checks");
    }

    /** 1. sanitizeLanguage 对合法标签原样透传（不自行改写大小写、不额外归一化） */
    private static void sanitizedTagsKeptVerbatim() {
        for (String tag : List.of("zh_cn", "ZH_CN", "Zh_Cn", "zh", "ZH", "en_us", "EN_US", "ja_jp", "JA_JP")) {
            check(SelfTalkContexts.sanitizeLanguage(tag).equals(tag), "合法标签应原样透传：" + tag);
        }
    }

    /** 2. 判定结果大小写不敏感：同一标签的大小写变体必须给出同一模板语言 */
    private static void caseVariantsSelectSameLanguage() {
        for (String zh : List.of("zh_cn", "ZH_CN", "Zh_Cn", "ZH_cn", "zh", "ZH")) {
            check(SelfTalkContexts.languageOf(zh).isZh(), "中文标签大小写变体应选 ZH：" + zh);
        }
        for (String other : List.of("en_us", "EN_US", "En_Us", "ja_jp", "JA_JP", "ko_kr")) {
            check(!SelfTalkContexts.languageOf(other).isZh(), "非中文标签大小写变体不得选 ZH：" + other);
        }
        // 等价输入必须落到同一枚举，而不是「一个 ZH 一个 EN」
        check(SelfTalkContexts.languageOf("ZH_CN") == SelfTalkContexts.languageOf("zh_cn"),
                "ZH_CN 与 zh_cn 必须得到同一模板语言");
        check(SelfTalkContexts.languageOf("EN_US") == SelfTalkContexts.languageOf("en_us"),
                "EN_US 与 en_us 必须得到同一模板语言");
    }

    /** 3. 输出语言指令与背景模板同口径：不会出现「背景中文、指令走通用分支」的分裂 */
    private static void instructionAgreesWithTemplateLanguage() {
        String zhInstruction = SelfTalkContexts.languageInstruction("zh_cn");
        String enInstruction = SelfTalkContexts.languageInstruction("en_us");
        for (String zh : List.of("zh_cn", "ZH_CN", "Zh_Cn", "zh", "ZH")) {
            check(SelfTalkContexts.languageInstruction(zh).equals(zhInstruction),
                    "中文大小写变体应得到同一输出语言指令：" + zh);
        }
        for (String other : List.of("en_us", "EN_US", "En_Us")) {
            // 非中文统一走非中文路径；具体文本允许 Locale 通用分支（如 English (United States)），
            // 不要求与 en_us 逐字相同，但绝不能落到中文固定指令
            check(!SelfTalkContexts.languageInstruction(other).equals(zhInstruction),
                    "非中文大小写变体不得得到中文输出语言指令：" + other);
        }
        check(!SelfTalkContexts.languageInstruction("ZH_CN").equals(enInstruction),
                "中文标签不得走英文输出指令");
        // 背景模板与指令同口径：同一标签不可能一边中文一边英文
        for (String lang : List.of("zh_cn", "ZH_CN", "en_us", "EN_US", "ja_jp")) {
            boolean bgZh = ContextLanguage.of(lang).isZh();
            boolean instrZh = SelfTalkContexts.languageInstruction(lang).equals(zhInstruction);
            check(bgZh == instrZh, "背景模板与输出指令语言不一致：" + lang);
        }
        // 日语等非中英语言的既有语义：背景走英文，但输出语言指令仍要求日语
        check(!ContextLanguage.of("ja_jp").isZh(), "日语背景模板应走英文");
        check(!SelfTalkContexts.languageInstruction("ja_jp").equals(enInstruction)
                        && !SelfTalkContexts.languageInstruction("ja_jp").equals(zhInstruction),
                "日语输出指令应要求日语，而不是中英固定指令");
    }

    /** 4. 非法/缺失标签的既有回退不变：仍落到 zh_cn，不新增默认语言 */
    private static void illegalAndMissingKeepExistingFallback() {
        // null 单独处理：sanitizeLanguage 的契约只覆盖「非法字符串」，null 由调用方各自兜底
        // （TLM 侧空白语言先替换为 en_us / 配置默认值再传入），这里不把 null 当成非法标签
        for (String illegal : new String[] {"", "  ", "zh cn", "<script>", "zh-cn-extra-long", "中文"}) {
            check(SelfTalkContexts.sanitizeLanguage(illegal).equals("zh_cn"),
                    "非法或缺失标签应回退 zh_cn：" + illegal);
            check(SelfTalkContexts.languageOf(illegal).isZh(),
                    "回退值应落到中文模板：" + illegal);
            check(SelfTalkContexts.languageInstruction(SelfTalkContexts.sanitizeLanguage(illegal))
                            .equals(SelfTalkContexts.languageInstruction("zh_cn")),
                    "回退值应得到中文输出语言指令：" + illegal);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }
}
