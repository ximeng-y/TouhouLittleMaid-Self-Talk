package com.maidmod.selftalk.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 关键词规划输出的解析（纯文本，无 Minecraft 依赖）。
 * <p>
 * 唯一有效形状：
 * {@code {"history_keywords":["下界","恶魂"],"persona_keywords":["钓鱼"]}}。
 * <p>
 * 两组关键词来自同一次规划请求：历史召回与人设召回分别检索，因此两个字段都<b>必须存在</b>
 * 且都是字符串数组（未启用的目标要求返回空数组）。缺字段即判为格式错误并进入纠正——
 * 「少给一组」不能当成「那组为空」，否则提示词里的两条硬要求形同虚设。
 * <p>
 * 允许的规范化<b>仅</b>三项：去首尾空白；整个输出被<b>一层</b> Markdown 代码围栏包裹时剥掉这一层；
 * 用 Gson 的严格流式读取解析一个完整 JSON 对象。除此之外一律判为格式错误：
 * <b>不</b>补括号、不替换单引号、不用正则捞字符串、不把自然语言长句擅自拆成关键词。
 * <p>
 * 严格性由三处共同保证：
 * <ul>
 *   <li>{@code JsonReader.setLenient(false)}（严格模式：单引号、无引号键、注释、尾随逗号一律拒绝）；</li>
 *   <li>自行递归读取整个值（不用 {@code JsonParser.parseString} 这类宽松入口，
 *       它对重复字段取后者、对尾随内容视而不见），读完后确认输入已到 {@code END_DOCUMENT}；</li>
 *   <li>两个关键词字段各自在对象内只允许出现一次（重复即判错）。</li>
 * </ul>
 * 无关的额外字段予以忽略（只取这两个字段）。
 * <p>
 * 目标环境为 gson 2.10.1（MC 1.21.1 与 1.20.1 均自带该版本），该版本尚无
 * {@code Strictness} 枚举，因此严格性统一用 {@code setLenient(false)} 表达。
 */
public final class KeywordPlanParser {

    /** 历史关键词字段名 */
    public static final String FIELD_HISTORY = "history_keywords";
    /** 人设关键词字段名 */
    public static final String FIELD_PERSONA = "persona_keywords";

    private KeywordPlanParser() {
    }

    /**
     * 解析结果。
     *
     * @param historyKeywords 已去空白、去空项、忽略大小写去重并截断到上限的历史关键词
     * @param personaKeywords 同上，人设关键词
     * @param error           错误说明；{@code null} 表示解析成功。成功但两个数组都为空时同样为 null
     *                        （空数组是合法输出，不触发纠正）
     */
    public record Result(List<String> historyKeywords, List<String> personaKeywords, String error) {

        public boolean ok() {
            return error == null;
        }

        static Result success(List<String> historyKeywords, List<String> personaKeywords) {
            return new Result(historyKeywords, personaKeywords, null);
        }

        static Result failure(String error) {
            return new Result(List.of(), List.of(), error);
        }
    }

    public static Result parse(String raw) {
        if (raw == null) {
            return Result.failure("输出为空");
        }
        String text = stripSingleFence(raw.trim());
        if (text.isEmpty()) {
            return Result.failure("输出为空");
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement element = readWholeValue(reader);
            if (element == null || !element.isJsonObject()) {
                return Result.failure("顶层不是 JSON 对象");
            }
            JsonObject object = element.getAsJsonObject();
            List<String> history = readKeywordArray(object, FIELD_HISTORY);
            if (history == null) {
                return Result.failure("缺少 " + FIELD_HISTORY + " 字段或它不是字符串数组");
            }
            List<String> persona = readKeywordArray(object, FIELD_PERSONA);
            if (persona == null) {
                return Result.failure("缺少 " + FIELD_PERSONA + " 字段或它不是字符串数组");
            }
            return Result.success(history, persona);
        } catch (IOException | RuntimeException e) {
            return Result.failure("无法解析为 JSON：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 读取一个关键词数组字段：去空白、去空项、忽略大小写去重、截断到上限。
     *
     * @return 结果列表；字段缺失或不是字符串数组时返回 {@code null}（由调用方转成具体错误说明）
     */
    private static List<String> readKeywordArray(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        List<String> cleaned = new ArrayList<>(array.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement item : array) {
            if (item == null || !item.isJsonPrimitive() || !((JsonPrimitive) item).isString()) {
                return null;
            }
            String value = item.getAsString().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (seen.add(value.toLowerCase(Locale.ROOT))) {
                cleaned.add(value);
            }
        }
        if (cleaned.size() > HistoryRetrievalIndex.MAX_KEYWORDS) {
            // 超量本身不触发纠正：截取前 N 个即可
            cleaned = new ArrayList<>(cleaned.subList(0, HistoryRetrievalIndex.MAX_KEYWORDS));
        }
        return List.copyOf(cleaned);
    }

    /** 严格读取一个完整 JSON 值，并确认输入没有剩余内容 */
    private static JsonElement readWholeValue(JsonReader reader) throws IOException {
        JsonToken first = reader.peek();
        if (first == null || first == JsonToken.END_DOCUMENT) {
            return null;
        }
        JsonElement element = readElement(reader);
        // 尾随解释文字、第二个 JSON 都会在此留下未消费 token
        JsonToken tail = reader.peek();
        if (tail != JsonToken.END_DOCUMENT && tail != null) {
            throw new IOException("JSON 之后仍有内容");
        }
        return element;
    }

    /** 逐个 token 自行递归读取：不使用任何会自动宽松解析的入口 */
    private static JsonElement readElement(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == null) {
            throw new IOException("JSON 意外结束");
        }
        switch (token) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) {
                        throw new IOException("重复字段 " + name);
                    }
                    object.add(name, readElement(reader));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(readElement(reader));
                }
                reader.endArray();
                return array;
            }
            case STRING -> {
                return new JsonPrimitive(reader.nextString());
            }
            case NUMBER -> {
                return new JsonPrimitive(new BigDecimal(reader.nextString()));
            }
            case BOOLEAN -> {
                return new JsonPrimitive(reader.nextBoolean());
            }
            case NULL -> {
                reader.nextNull();
                return com.google.gson.JsonNull.INSTANCE;
            }
            default -> throw new IOException("意外的 token " + token);
        }
    }

    /**
     * 剥掉一层 Markdown 代码围栏。
     * <p>
     * 只在整段输出恰好被一对围栏包住时生效：首行是 {@code ```} 或 {@code ```json}，
     * 末行是 {@code ```}。多于一层的围栏、围栏前后还有解释文字都不处理。
     */
    static String stripSingleFence(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        if (firstLineEnd < 0) {
            return trimmed;
        }
        String opener = trimmed.substring(3, firstLineEnd).trim();
        if (!opener.isEmpty() && !"json".equalsIgnoreCase(opener)) {
            return trimmed;
        }
        String body = trimmed.substring(firstLineEnd + 1);
        int lastFence = body.lastIndexOf("```");
        if (lastFence < 0 || !body.substring(lastFence + 3).trim().isEmpty()) {
            return trimmed;
        }
        return body.substring(0, lastFence).trim();
    }

    /**
     * 宽松解析入口（Gson 默认宽容行为）。
     * <p>
     * 生产路径<b>不</b>使用；仅作为自检对照存在——用来证明「同一个输入宽松解析会接受、
     * 严格解析会拒绝」，从而说明本类的严格性检查确实生效，而不是空转。
     */
    public static JsonElement lenientParse(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
