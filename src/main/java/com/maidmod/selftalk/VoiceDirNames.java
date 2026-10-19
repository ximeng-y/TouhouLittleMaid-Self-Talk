package com.maidmod.selftalk;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * 预制语音缓存的声音目录名：可读的「供应商-音色」加上完整声音标识键的短哈希。
 * <p>
 * 完整声音标识仍由 {@link PrebuiltAudioStore#voiceKeyOf} 的 SHA-256 决定；本类只负责把
 * 供应商类型与显示音色变成可读、可落盘、跨平台稳定的目录名。显示音色优先取站点
 * {@code SupportModelSelect.models()} 的显示名，缺失或清洗后不可用时回退音色 ID，
 * 仍不可用则回退 {@code voice}。哈希始终追加在最后，因此清洗、截断或大小写差异都不会
 * 把两个不同声音混到同一目录。
 * <p>
 * 非法字符处理：
 * <ul>
 *   <li>Windows 不允许的 {@code / \ : * ? " < > |} 全部替换为 {@code _}；</li>
 *   <li>NUL、换行、其余控制字符与 bidi/零宽等格式字符替换为 {@code _}；</li>
 *   <li>中文、空格、点、全角标点等合法字符保留；</li>
 *   <li>先做 NFC 规范化，再修剪首尾空白与点；</li>
 *   <li>供应商最多 16 码点、音色最多 32 码点；最终目录名最多 64 个 UTF-16 单元、
 *       200 字节，按码点截断，不会截断代理对；</li>
 *   <li>清洗后没有任何字母、数字或组合标记时视为不可用，走 fallback；</li>
 *   <li>完整目录名若仍意外不可用，使用 {@code tts-voice-<hash>} 这个只含安全字符的兜底名。</li>
 * </ul>
 * 全部方法无共享状态、无文件系统访问，可在任何线程调用；调用方仍须按主线程约定使用结果。
 */
final class VoiceDirNames {

    /** 追加在目录名末尾的完整声音键前缀长度（48 bit，可忽略碰撞概率） */
    static final int HASH_LENGTH = 12;

    private static final int MAX_PROVIDER_CODE_POINTS = 16;
    private static final int MAX_VOICE_CODE_POINTS = 32;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_NAME_BYTES = 200;
    private static final String SEPARATOR = "-";
    private static final String FALLBACK_PREFIX = "tts-voice-";
    private static final String DEFAULT_PROVIDER = "tts";
    private static final String DEFAULT_VOICE = "voice";
    private static final char REPLACEMENT = '_';

    private VoiceDirNames() {
    }

    /** 可读主名与只含安全字符的兜底名；两者都带同一个短哈希 */
    record Names(String primary, String fallback) {
    }

    /**
     * 生成声音目录候选名。完整声音键必须是非空、64 位小写十六进制，否则返回 null
     * （调用方放弃本次音频缓存，不阻断正常交付）。
     */
    @Nullable
    static Names of(@Nullable String apiType, @Nullable String displayName,
                    @Nullable String modelId, @Nullable String fullKey) {
        String hash = hashPrefix(fullKey);
        if (hash == null) {
            return null;
        }
        String fallback = FALLBACK_PREFIX + hash;
        String provider = sanitizeLabel(apiType, MAX_PROVIDER_CODE_POINTS);
        if (!hasMeaningfulChar(provider)) {
            provider = DEFAULT_PROVIDER;
        }
        String voice = sanitizeLabel(displayName, MAX_VOICE_CODE_POINTS);
        if (!hasMeaningfulChar(voice)) {
            voice = sanitizeLabel(modelId, MAX_VOICE_CODE_POINTS);
        }
        if (!hasMeaningfulChar(voice)) {
            voice = DEFAULT_VOICE;
        }
        String base = provider + SEPARATOR + voice;
        if (isReservedWindowsName(base)) {
            base = REPLACEMENT + base;
        }
        String primary = fit(base, hash);
        if (primary == null || !isValidName(primary)) {
            primary = fallback;
        }
        return new Names(primary, fallback);
    }

    /**
     * 清洗单个显示段：NFC、替换非法/控制/格式字符、修剪首尾空白与点、按码点截断。
     * 清洗后没有有效内容（空、{@code .}/{@code ..}、只有标点或下划线）时返回空串。
     */
    static String sanitizeLabel(@Nullable String raw, int maxCodePoints) {
        if (raw == null || raw.isEmpty() || maxCodePoints <= 0) {
            return "";
        }
        String normalized;
        try {
            normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
        } catch (RuntimeException e) {
            normalized = raw;
        }
        StringBuilder out = new StringBuilder(normalized.length());
        int count = 0;
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                // 未配对代理项：不能原样写进路径，统一替换
                out.append(REPLACEMENT);
            } else if (Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT
                    || isWindowsIllegal(codePoint)) {
                out.append(REPLACEMENT);
            } else {
                out.appendCodePoint(codePoint);
            }
            count++;
            if (count >= maxCodePoints) {
                break;
            }
        }
        String value = trimEdgeDotsAndSpaces(out.toString());
        if (value.isEmpty() || ".".equals(value) || "..".equals(value) || !hasMeaningfulChar(value)) {
            return "";
        }
        return value;
    }

    /** 目录名最终校验：非空、非 . 或 ..、不含路径分隔符/控制字符、长度与字节数在限内 */
    static boolean isValidName(@Nullable String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return false;
        }
        if (name.length() > MAX_NAME_LENGTH
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            return false;
        }
        if (!name.equals(name.strip()) || name.endsWith(".")) {
            return false;
        }
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                return false;
            }
            if (Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT
                    || isWindowsIllegal(codePoint)) {
                return false;
            }
        }
        return !isReservedWindowsName(name);
    }

    /** 是否包含至少一个字母、数字或组合标记（纯标点/下划线不算可读音色） */
    static boolean hasMeaningfulChar(@Nullable String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isLetterOrDigit(codePoint)
                    || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                return true;
            }
        }
        return false;
    }

    /** Windows 设备保留名（不含扩展名时也要拦；目录名末尾的哈希会天然避开精确匹配） */
    static boolean isReservedWindowsName(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        int dot = name.indexOf('.');
        String base = (dot >= 0 ? name.substring(0, dot) : name).stripTrailing();
        if (base.isEmpty()) {
            return false;
        }
        String upper = base.toUpperCase(Locale.ROOT);
        if ("CON".equals(upper) || "PRN".equals(upper) || "AUX".equals(upper) || "NUL".equals(upper)) {
            return true;
        }
        if (upper.length() == 4 && (upper.startsWith("COM") || upper.startsWith("LPT"))) {
            char digit = upper.charAt(3);
            return digit >= '1' && digit <= '9';
        }
        return false;
    }

    /** 完整声音键必须为 64 位小写十六进制；只取前 {@link #HASH_LENGTH} 位 */
    @Nullable
    private static String hashPrefix(@Nullable String fullKey) {
        if (fullKey == null || fullKey.length() != 64) {
            return null;
        }
        for (int i = 0; i < fullKey.length(); i++) {
            char c = fullKey.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return null;
            }
        }
        return fullKey.substring(0, HASH_LENGTH);
    }

    /** 在总长度/字节数限制内保留尽量长的可读前缀，并始终保留末尾哈希 */
    @Nullable
    private static String fit(String base, String hash) {
        String shortened = base;
        while (!shortened.isEmpty()) {
            String candidate = shortened + SEPARATOR + hash;
            if (fits(candidate)) {
                return candidate;
            }
            shortened = trimTrailingForJoin(removeLastCodePoint(shortened));
            if (shortened.isEmpty() || !hasMeaningfulChar(shortened)) {
                return null;
            }
        }
        return null;
    }

    private static boolean fits(String name) {
        return name.length() <= MAX_NAME_LENGTH
                && name.getBytes(StandardCharsets.UTF_8).length <= MAX_NAME_BYTES;
    }

    private static String removeLastCodePoint(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(value.length(), -1));
    }

    /** 截断后可能留下空格、点或连接符，避免与哈希连接时出现无意义尾缀 */
    private static String trimTrailingForJoin(String value) {
        String trimmed = value.stripTrailing();
        int end = trimmed.length();
        while (end > 0) {
            char c = trimmed.charAt(end - 1);
            if (c == '.' || c == '-') {
                end--;
            } else {
                break;
            }
        }
        return trimmed.substring(0, end);
    }

    private static String trimEdgeDotsAndSpaces(String value) {
        String trimmed = value.strip();
        int start = 0;
        int end = trimmed.length();
        while (start < end && trimmed.charAt(start) == '.') {
            start++;
        }
        while (end > start && trimmed.charAt(end - 1) == '.') {
            end--;
        }
        return trimmed.substring(start, end).strip();
    }

    private static boolean isWindowsIllegal(int codePoint) {
        return codePoint == '/' || codePoint == '\\' || codePoint == ':'
                || codePoint == '*' || codePoint == '?' || codePoint == '"'
                || codePoint == '<' || codePoint == '>' || codePoint == '|';
    }
}
