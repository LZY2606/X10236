package org.neuroclust.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** 规范化序列化与输入摘要：键排序保证等价输入得到稳定摘要与稳定顺序。 */
public final class Canonical {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Canonical() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("canonical serialization failed", e);
        }
    }

    /** 递归排序 Map 键，使语义等价输入产生完全一致的 JSON。 */
    @SuppressWarnings("unchecked")
    public static Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), sort(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> it) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object o : it) out.add(sort(o));
            return out;
        }
        return value;
    }

    public static String digest(String canonicalJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String digestOf(Object value) {
        return digest(write(sort(value)));
    }
}
