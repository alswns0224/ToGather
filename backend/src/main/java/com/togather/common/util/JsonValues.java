package com.togather.common.util;

import java.util.List;
import java.util.Map;

/**
 * 외부 API의 JSON 응답(Map)에서 값을 꺼내는 도우미. 키가 없거나 타입이 다르면 null / 빈 값을 반환한다.
 */
public final class JsonValues {

    private JsonValues() {
    }

    public static String str(Map<String, Object> json, String key) {
        Object value = json == null ? null : json.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static Long longValue(Map<String, Object> json, String key) {
        Object value = json == null ? null : json.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    public static boolean bool(Map<String, Object> json, String key) {
        Object value = json == null ? null : json.get(key);
        return Boolean.TRUE.equals(value) || "true".equals(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> json, String key) {
        Object value = json == null ? null : json.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(Map<String, Object> json, String key) {
        Object value = json == null ? null : json.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
