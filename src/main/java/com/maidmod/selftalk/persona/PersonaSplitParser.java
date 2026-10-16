package com.maidmod.selftalk.persona;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.maidmod.selftalk.MaidPersonaDraft;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/**
 * AI 拆分输出的解析（纯文本，无 Minecraft 依赖）。
 * <p>
 * 唯一有效形状：{@code {"identity":"...","personality":"...","background":"...","hobbies":"..."}}。
 * <p>
 * 四个字段都<b>必须</b>存在且都是字符串；允许整段输出被<b>一层</b> Markdown 代码围栏包裹。
 * 除此之外一律判为格式错误：拒绝缺字段、重复字段、额外字段、非字符串值与尾随内容，
 * 四栏全部空白同样判错（缺少某类资料时该栏允许空串，但全空说明模型什么都没给出）。
 * <p>
 * 字段内容<b>不 trim、不清洗、不截断</b>——拆分要求逐字搬运原文，任何规范化都会丢内容
 * （与关键词规划的清洗策略刻意不同，本类不复用其去重/截断逻辑）。
 * <p>
 * 严格性由三处共同保证（与 {@code KeywordPlanParser} 同一流式读取技术）：
 * {@code JsonReader.setLenient(false)}；自行递归读取整个值并在读完后确认输入已到
 * {@code END_DOCUMENT}；对象内字段名重复即抛错。顶层额外字段按格式错误拒绝
 * （拆分输出形状完全固定，多出的字段说明模型没有遵守输出约定）。
 */
public final class PersonaSplitParser {

    public static final String FIELD_IDENTITY = "identity";
    public static final String FIELD_PERSONALITY = "personality";
    public static final String FIELD_BACKGROUND = "background";
    public static final String FIELD_HOBBIES = "hobbies";

    private PersonaSplitParser() {
    }

    /**
     * 解析结果。
     *
     * @param draft 拆分出的四栏草稿（失败时为 {@link MaidPersonaDraft#EMPTY} 占位）
     * @param error 错误说明；{@code null} 表示解析成功
     */
    public record Result(MaidPersonaDraft draft, String error) {

        public boolean ok() {
            return error == null;
        }

        static Result success(MaidPersonaDraft draft) {
            return new Result(draft, null);
        }

        static Result failure(String error) {
            return new Result(MaidPersonaDraft.EMPTY, error);
        }
    }

    public static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
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
            String identity = readField(object, FIELD_IDENTITY);
            if (identity == null) {
                return Result.failure("缺少 " + FIELD_IDENTITY + " 字段或它不是字符串");
            }
            String personality = readField(object, FIELD_PERSONALITY);
            if (personality == null) {
                return Result.failure("缺少 " + FIELD_PERSONALITY + " 字段或它不是字符串");
            }
            String background = readField(object, FIELD_BACKGROUND);
            if (background == null) {
                return Result.failure("缺少 " + FIELD_BACKGROUND + " 字段或它不是字符串");
            }
            String hobbies = readField(object, FIELD_HOBBIES);
            if (hobbies == null) {
                return Result.failure("缺少 " + FIELD_HOBBIES + " 字段或它不是字符串");
            }
            if (object.size() != 4) {
                return Result.failure("存在额外字段");
            }
            MaidPersonaDraft draft = new MaidPersonaDraft(identity, personality, background, hobbies);
            if (draft.identity().isBlank() && draft.personality().isBlank()
                    && draft.background().isBlank() && draft.hobbies().isBlank()) {
                return Result.failure("四栏全部为空白");
            }
            return Result.success(draft);
        } catch (IOException | RuntimeException e) {
            return Result.failure("无法解析为 JSON：" + e.getClass().getSimpleName());
        }
    }

    /** 读取字符串字段：缺失或非字符串返回 {@code null}（由调用方转成具体错误说明）；内容原样保留 */
    private static String readField(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !((JsonPrimitive) element).isString()) {
            return null;
        }
        return element.getAsString();
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

    /** 逐个 token 自行递归读取：不使用任何会自动宽松解析的入口；重复字段名直接抛错 */
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
}
