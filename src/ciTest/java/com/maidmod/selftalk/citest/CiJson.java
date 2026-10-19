package com.maidmod.selftalk.citest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** JSON 与反射小工具：测试驱动只读产品状态，不修改产品内部字段 */
public final class CiJson {

    private CiJson() {
    }

    public static String str(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    public static String str(JsonObject object, String key, String fallback) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    public static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
    }

    public static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    public static double number(JsonObject object, String key, double fallback) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
    }

    public static JsonObject obj(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    public static JsonArray arr(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    public static JsonObject object() {
        return new JsonObject();
    }

    public static JsonObject of(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    public static JsonArray array(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    public static JsonElement primitive(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement element) {
            return element;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    /** 读取指定类层级中的字段（产品字段为 private 时仅用于观测，不做写入） */
    public static Object field(Object target, Class<?> owner, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = owner;
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    public static String textOf(Object component) {
        if (component == null) {
            return "";
        }
        try {
            Method method = component.getClass().getMethod("getString");
            Object value = method.invoke(component);
            return value == null ? "" : value.toString();
        } catch (Throwable t) {
            return String.valueOf(component);
        }
    }
}
