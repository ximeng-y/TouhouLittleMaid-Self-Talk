package com.maidmod.selftalk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 死亡消息的显式英文渲染（issue #23）。
 * <p>
 * 为什么不能直接用 {@code getLocalizedDeathMessage(...).getString()}：那个结果跟随游戏全局语言，
 * 专用服务端恒为英文、单人／局域网随客户端语言，无法按聊天语言稳定选择。
 * <p>
 * 本类因此独立读取<b>原版英文映射</b> {@code /assets/minecraft/lang/en_us.json}：
 * <ul>
 *   <li>走与 {@link Language} 默认加载<b>同一个</b> classpath 资源入口（{@code Language.class.getResourceAsStream}），
 *       不使用服务端 data {@code ResourceManager} 去读客户端 assets；</li>
 *   <li><b>不</b>调用 {@link Language#inject}，不改变服务器或客户端的全局语言；</li>
 *   <li>映射进程内只加载一次，纯读取：不联网下载、不写文件；</li>
 *   <li>递归处理 {@code TranslatableContents} 的 key、fallback 与参数，支持原版翻译里的
 *       {@code %s}、位置参数 {@code %1$s} 与百分号转义 {@code %%}；</li>
 *   <li>保留 literal 内容、参数中的 Component 与 siblings 的可见文本。</li>
 * </ul>
 * 已知边界（不隐瞒）：
 * <ul>
 *   <li>自定义名称（玩家名、命名牌名称、自定义武器名）是字面数据，<b>不翻译</b>；</li>
 *   <li>第三方直接提供的字面死亡文本、缺失英文键或不支持的 Component，一律返回 {@code null}——
 *       调用方保留原有内容（中文路径），不删事件、不猜译。这类外部文本可能仍非英文，
 *       本类不承诺任意第三方死亡文本都能自动翻译；</li>
 *   <li>classpath 英文资源在真实专用服上的可用性尚未实机验收（当前只确认了开发资源与原版加载路径）。</li>
 * </ul>
 */
final class DeathMessageEnglishRenderer {

    /** 原版英文映射的 classpath 路径，与 {@link Language} 默认加载同一入口 */
    private static final String ENGLISH_LANG_PATH = "/assets/minecraft/lang/en_us.json";
    /**
     * 原版 {@link Language} 对不受支持的 {@code %d}／{@code %f} 的归一化规则（与上游同式）。
     * 不照做会让英文表里形如 {@code You have %d} 的条目在我们这儿解析失败。
     */
    private static final Pattern UNSUPPORTED_FORMAT_PATTERN = Pattern.compile("%(\\d+\\$)?[\\d.]*[df]");
    /** 原版 {@code TranslatableContents} 的格式占位符模式，逐字对齐 */
    private static final Pattern FORMAT_PATTERN = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

    private DeathMessageEnglishRenderer() {
    }

    /**
     * 把死亡消息 Component 渲染成英文文本。
     *
     * @return 英文文本；无法完整渲染（缺键、格式不支持、含不可解析的占位符）时返回 {@code null}，
     *         调用方应保留原有内容
     */
    static String render(Component message) {
        if (message == null) {
            return null;
        }
        try {
            return renderComponent(message);
        } catch (UnsupportedMessageException e) {
            return null;
        }
    }

    /** 组件文本 = 自身内容 + 各 sibling 的可见文本（与 {@code Component.getString()} 同构） */
    private static String renderComponent(Component component) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderContents(component.getContents()));
        for (Component sibling : component.getSiblings()) {
            sb.append(renderComponent(sibling));
        }
        return sb.toString();
    }

    private static String renderContents(ComponentContents contents) {
        if (contents instanceof TranslatableContents translatable) {
            return renderTranslatable(translatable);
        }
        // 非可翻译内容（字面文本、score、selector 等）：保留其可见文本，不做二次解释
        return plainText(contents);
    }

    /**
     * 渲染一条可翻译内容：查英文表 → 逐字处理占位符 → 递归渲染参数。
     * <p>
     * 查表口径与上游 {@code TranslatableContents.decompose} 一致：先 key，缺失时用自身 fallback，
     * 都没有则视为缺键（本类返回不可渲染，由调用方保留原内容）。
     */
    private static String renderTranslatable(TranslatableContents translatable) {
        String key = translatable.getKey();
        String template = englishTable().get(key);
        if (template == null) {
            template = translatable.getFallback();
        }
        if (template == null) {
            throw new UnsupportedMessageException();
        }
        return format(template, translatable.getArgs());
    }

    /**
     * 占位符替换：与原版算法同构（{@code %s} 顺序取参、{@code %1$s} 位置取参、{@code %%} 转义）。
     * <p>
     * 不支持的格式或参数越界都视为无法渲染——宁可退回原内容，也不猜一个可能错的英文句子。
     */
    private static String format(String template, Object[] args) {
        Matcher matcher = FORMAT_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 16);
        int next = 0;
        int end = 0;
        while (matcher.find(end)) {
            int start = matcher.start();
            if (start > end) {
                String literal = template.substring(end, start);
                if (literal.indexOf('%') >= 0) {
                    throw new UnsupportedMessageException();
                }
                sb.append(literal);
            }
            String group = matcher.group(2);
            String matched = template.substring(start, matcher.end());
            if ("%".equals(group) && "%%".equals(matched)) {
                sb.append('%');
            } else if (!"s".equals(group)) {
                throw new UnsupportedMessageException();
            } else {
                String index = matcher.group(1);
                int argIndex = index != null ? Integer.parseInt(index) - 1 : next++;
                if (argIndex < 0 || argIndex >= args.length) {
                    throw new UnsupportedMessageException();
                }
                sb.append(renderArgument(args[argIndex]));
            }
            end = matcher.end();
        }
        if (end < template.length()) {
            String tail = template.substring(end);
            if (tail.indexOf('%') >= 0) {
                throw new UnsupportedMessageException();
            }
            sb.append(tail);
        }
        return sb.toString();
    }

    /** 参数渲染：Component 递归翻译，其余按 {@code toString()}（与原版 {@code getArgument} 一致） */
    private static String renderArgument(Object arg) {
        if (arg instanceof Component component) {
            return renderComponent(component);
        }
        return arg == null ? "null" : arg.toString();
    }

    /** 非可翻译内容的可见文本：遍历其文本片段（不做语言替换） */
    private static String plainText(ComponentContents contents) {
        StringBuilder sb = new StringBuilder();
        contents.visit((String text) -> {
            sb.append(text);
            return java.util.Optional.empty();
        });
        return sb.toString();
    }

    // ===== 英文映射加载（进程内一次） =====

    private static volatile Map<String, String> englishTable;

    /** 原版英文映射（只读，缺失时为空表；空表会让所有死亡消息保留原内容，不影响其它功能） */
    private static Map<String, String> englishTable() {
        Map<String, String> table = englishTable;
        if (table != null) {
            return table;
        }
        synchronized (DeathMessageEnglishRenderer.class) {
            if (englishTable == null) {
                englishTable = loadEnglish();
            }
            return englishTable;
        }
    }

    /**
     * 读取 {@code /assets/minecraft/lang/en_us.json}。
     * <p>
     * 与 {@link Language#loadFromJson} 一样，把 {@code %d}/{@code %f} 归一化为 {@code %s}——
     * 否则英文表里的这类占位符在本类的格式解析里会失败。
     * 只收字符串项：同名文件里数组形式的条目交由原版组件序列化处理，本类不做二次解释。
     */
    private static Map<String, String> loadEnglish() {
        Map<String, String> table = new java.util.HashMap<>();
        try (InputStream stream = Language.class.getResourceAsStream(ENGLISH_LANG_PATH)) {
            if (stream == null) {
                MaidSelfTalkMod.LOGGER.warn("Vanilla English language file is not on the classpath: {}",
                        ENGLISH_LANG_PATH);
                return Map.of();
            }
            JsonObject json = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    table.put(entry.getKey(),
                            UNSUPPORTED_FORMAT_PATTERN.matcher(value.getAsString()).replaceAll("%$1s"));
                }
            }
        } catch (IOException | RuntimeException e) {
            // 读失败只影响死亡消息的英文渲染：调用方会保留原内容，其它功能不受影响
            MaidSelfTalkMod.LOGGER.warn("Failed to read vanilla English language file {}", ENGLISH_LANG_PATH, e);
            return Map.of();
        }
        MaidSelfTalkMod.LOGGER.info("Loaded {} vanilla English translation entries for death messages", table.size());
        return Map.copyOf(table);
    }

    /** 无法完整渲染的信号：只在类内部使用，不外泄成异常类型 */
    private static final class UnsupportedMessageException extends RuntimeException {

        private UnsupportedMessageException() {
            super(null, null, false, false);
        }
    }
}
