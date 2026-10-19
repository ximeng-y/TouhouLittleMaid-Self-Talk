package com.maidmod.selftalk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 声音目录名自检（无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * 只覆盖纯 Java 的 {@link VoiceDirNames}：可读名、非法字符替换、空/点回退、超长与 emoji
 * 截断、NFC、Windows 保留名、哈希区分与完整键校验。刻意不引用 TLM 类，NeoForge 线的
 * 类校验不会影响本自检。
 */
public final class VoiceDirNamesTest {

    private static final String KEY_A = "0123456789abcdef".repeat(4);
    private static final String KEY_B = "fedcba9876543210".repeat(4);
    private static final int HASH_LENGTH = 12;
    private static int checks = 0;

    private VoiceDirNamesTest() {
    }

    public static void main(String[] args) {
        readablePresetKeepsDisplayName();
        illegalCharactersAreReplaced();
        emptyAndDotsUseFallback();
        modelIdFallbackReplacesColon();
        longAndAstralLabelsStayWithinLimits();
        controlAndFormatCharactersAreReplaced();
        hashKeepsDifferentVoicesApart();
        invalidKeysAreRejected();
        validationAndReservedNames();
        nfcAndTrimming();
        System.out.println("VoiceDirNamesTest passed: " + checks + " checks");
    }

    private static void readablePresetKeepsDisplayName() {
        VoiceDirNames.Names names = VoiceDirNames.of(
                "mimo", "冰糖（中文女）", "preset:冰糖", KEY_A);
        check(names != null, "正常声音应生成目录名");
        check(names.primary().equals("mimo-冰糖（中文女）-" + hash(KEY_A)),
                "可读显示名与哈希应组成主名：" + names.primary());
        check(names.fallback().equals("tts-voice-" + hash(KEY_A)),
                "兜底名应为 tts-voice-哈希：" + names.fallback());
        checkValid(names.primary());
    }

    private static void illegalCharactersAreReplaced() {
        String raw = "测试/音色\\女:*?\"<>|.wav";
        VoiceDirNames.Names names = VoiceDirNames.of("mimo", raw, null, KEY_A);
        check(names != null, "含非法字符的声音仍应生成目录名");
        check(!names.primary().contains("/") && !names.primary().contains("\\"),
                "路径分隔符必须替换：" + names.primary());
        for (char c : new char[]{':', '*', '?', '"', '<', '>', '|'}) {
            check(names.primary().indexOf(c) < 0, "Windows 非法字符必须替换：" + names.primary());
        }
        check(names.primary().contains("测试"), "中文可读部分应保留：" + names.primary());
        check(names.primary().contains("音色"), "中文可读部分应保留：" + names.primary());
        checkValid(names.primary());

        check(VoiceDirNames.sanitizeLabel("测试/音色", 32).equals("测试_音色"),
                "单个斜杠应替换为下划线");
    }

    private static void emptyAndDotsUseFallback() {
        VoiceDirNames.Names empty = VoiceDirNames.of("", "", null, KEY_A);
        check(empty != null, "空显示名也应有兜底目录名");
        check(empty.primary().equals(empty.fallback()),
                "供应商/音色都不可用时主名应使用兜底名");
        check(empty.primary().equals("tts-voice-" + hash(KEY_A)),
                "空显示名应使用 tts-voice-哈希：" + empty.primary());
        checkValid(empty.primary());

        VoiceDirNames.Names dots = VoiceDirNames.of("mimo", "..", null, KEY_A);
        check(dots != null, "点声音名应回退而不是生成 . / ..");
        check(!dots.primary().equals(".") && !dots.primary().equals(".."),
                "目录名不能是 . 或 ..：" + dots.primary());
        check(dots.primary().equals("mimo-voice-" + hash(KEY_A)),
                "不可读音色应回退 voice：" + dots.primary());
        check(VoiceDirNames.sanitizeLabel("...", 32).isEmpty(), "纯点应清洗为空");
        check(VoiceDirNames.sanitizeLabel("/", 32).isEmpty(), "纯非法字符应清洗为空");
    }

    private static void modelIdFallbackReplacesColon() {
        VoiceDirNames.Names names = VoiceDirNames.of("mimo", null, "preset:冰糖", KEY_A);
        check(names != null, "显示名缺失时应回退音色 ID");
        check(names.primary().equals("mimo-preset_冰糖-" + hash(KEY_A)),
                "回退音色 ID 的冒号应替换：" + names.primary());
        checkValid(names.primary());
    }

    private static void longAndAstralLabelsStayWithinLimits() {
        for (String label : List.of("测试".repeat(100), "a" + "😀".repeat(100),
                "a".repeat(500), "测试/".repeat(100))) {
            VoiceDirNames.Names names = VoiceDirNames.of("mimo", label, null, KEY_A);
            check(names != null, "超长标签仍应生成目录名");
            checkValid(names.primary());
            check(names.primary().endsWith("-" + hash(KEY_A)),
                    "截断后必须保留哈希：" + names.primary());
        }
        check(VoiceDirNames.sanitizeLabel("a".repeat(40), 10).length() == 10,
                "按码点截断普通字符");
        String emoji = VoiceDirNames.sanitizeLabel("a" + "😀".repeat(40), 10);
        check(emoji.codePointCount(0, emoji.length()) == 10,
                "emoji 截断按码点而不是 UTF-16 单元");
        check(noUnpairedSurrogates(emoji), "emoji 截断不得留下半个代理对");
    }

    private static void controlAndFormatCharactersAreReplaced() {
        VoiceDirNames.Names names = VoiceDirNames.of("mimo", "a\u0000b\nc\u202Ed", null, KEY_A);
        check(names != null, "控制字符声音仍应生成目录名");
        checkValid(names.primary());
        check(names.primary().contains("_"), "控制字符应替换为下划线：" + names.primary());
    }

    private static void hashKeepsDifferentVoicesApart() {
        VoiceDirNames.Names a = VoiceDirNames.of("mimo", "冰糖", "preset:冰糖", KEY_A);
        VoiceDirNames.Names b = VoiceDirNames.of("mimo", "冰糖", "preset:冰糖", KEY_B);
        check(a != null && b != null, "两个完整键都应生成目录名");
        check(!a.primary().equals(b.primary()), "同显示名不同完整键必须落到不同目录");
        check(a.primary().endsWith("-" + hash(KEY_A)), "A 目录名应带 A 哈希");
        check(b.primary().endsWith("-" + hash(KEY_B)), "B 目录名应带 B 哈希");
    }

    private static void invalidKeysAreRejected() {
        check(VoiceDirNames.of("mimo", "x", null, null) == null, "null 完整键应拒绝");
        check(VoiceDirNames.of("mimo", "x", null, "abc") == null, "短完整键应拒绝");
        check(VoiceDirNames.of("mimo", "x", null, "Z".repeat(64)) == null, "大写/非十六进制应拒绝");
        check(VoiceDirNames.of("mimo", "x", null, KEY_A) != null, "合法完整键应接受");
    }

    private static void validationAndReservedNames() {
        check(VoiceDirNames.isValidName("mimo-冰糖-" + hash(KEY_A)), "正常目录名应通过最终校验");
        check(!VoiceDirNames.isValidName("a/b"), "含 / 的名称应拒绝");
        check(!VoiceDirNames.isValidName("a:b"), "含 : 的名称应拒绝");
        check(!VoiceDirNames.isValidName("a\u0000b"), "含 NUL 的名称应拒绝");
        check(!VoiceDirNames.isValidName(""), "空名称应拒绝");
        check(!VoiceDirNames.isValidName("."), ". 应拒绝");
        check(!VoiceDirNames.isValidName("mimo-a-" + hash(KEY_A) + " "), "尾随空格应拒绝");
        check(!VoiceDirNames.isValidName("mimo-a-" + hash(KEY_A) + "."), "尾随点应拒绝");

        check(VoiceDirNames.isReservedWindowsName("CON"), "CON 应识别为保留名");
        check(VoiceDirNames.isReservedWindowsName("con.txt"), "con.txt 应识别为保留名");
        check(VoiceDirNames.isReservedWindowsName("COM1"), "COM1 应识别为保留名");
        check(VoiceDirNames.isReservedWindowsName("LPT9.log"), "LPT9.log 应识别为保留名");
        check(!VoiceDirNames.isReservedWindowsName("mimo-CON"), "带前缀的名称不是保留名");
        check(!VoiceDirNames.isReservedWindowsName("COM0"), "COM0 不是 Windows 保留名");
    }

    private static void nfcAndTrimming() {
        check(VoiceDirNames.sanitizeLabel("e\u0301", 32).equals("\u00E9"),
                "显示名应先做 NFC 规范化");
        check(VoiceDirNames.sanitizeLabel("  a.b  ", 32).equals("a.b"),
                "首尾空白应修剪");
        check(VoiceDirNames.sanitizeLabel(".a.", 32).equals("a"),
                "首尾点应修剪");
        check(VoiceDirNames.sanitizeLabel("（）", 32).isEmpty(),
                "只有标点的显示名应视为不可读");
    }

    private static String hash(String key) {
        return key.substring(0, HASH_LENGTH);
    }

    private static void checkValid(String name) {
        check(name != null && !name.isEmpty(), "目录名不能为空");
        check(name.length() <= 64, "目录名 UTF-16 长度应不超过 64：" + name);
        check(name.getBytes(StandardCharsets.UTF_8).length <= 200,
                "目录名 UTF-8 字节数应不超过 200：" + name);
        check(name.equals(name.strip()), "目录名不得有首尾空白：" + name);
        check(!name.endsWith("."), "目录名不得以点结尾：" + name);
        for (char c : new char[]{'/', '\\', ':', '*', '?', '"', '<', '>', '|'}) {
            check(name.indexOf(c) < 0, "目录名仍含非法字符 " + c + "：" + name);
        }
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            i += Character.charCount(codePoint);
            check(!Character.isISOControl(codePoint), "目录名仍含控制字符：" + name);
            check(Character.getType(codePoint) != Character.FORMAT, "目录名仍含格式字符：" + name);
        }
        check(noUnpairedSurrogates(name), "目录名含未配对代理项：" + name);
        check(VoiceDirNames.isValidName(name), "目录名未通过最终校验：" + name);
        try {
            Path.of(name);
        } catch (RuntimeException e) {
            check(false, "目录名不能构造 Path：" + name + " (" + e + ")");
        }
    }

    private static boolean noUnpairedSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }
}
