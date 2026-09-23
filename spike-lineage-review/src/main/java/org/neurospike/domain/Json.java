package org.neurospike.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;
import java.util.Map;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json write failed", e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json write failed", e);
        }
    }

    public static JsonNode readTree(String text) {
        try {
            return MAPPER.readTree(text == null ? "{}" : text);
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed json: " + e.getMessage(), e);
        }
    }

    public static <T> T read(String text, Class<T> type) {
        try {
            return MAPPER.readValue(text == null ? "{}" : text, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed json: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> map(String text) {
        try {
            return MAPPER.readValue(text == null ? "{}" : text, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed json: " + e.getMessage(), e);
        }
    }

    public static List<Object> list(String text) {
        try {
            return MAPPER.readValue(text == null || text.isBlank() ? "[]" : text,
                    new TypeReference<>() {
                    });
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed json: " + e.getMessage(), e);
        }
    }
}
