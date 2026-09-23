package org.neuro.spike.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;

/** JSON helpers plus a canonical (sorted-key) SHA-256 digest for content addressing. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json encode failed", e);
        }
    }

    public static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json == null ? "null" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid json: " + e.getOriginalLocation(), e);
        }
    }

    public static byte[] bytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    /** Deterministic rendering: keys sorted, no pretty printing. */
    public static String canonical(Object value) {
        try {
            Object normalized = MAPPER.convertValue(value, TreeMap.class);
            return MAPPER.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical encode failed", e);
        }
    }

    public static String sha256(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    public static String digest(Object value) {
        return sha256(canonical(value));
    }

    @SuppressWarnings("unchecked")
    public static <T> T parse(String json, Class<T> type) {
        try {
            return (T) MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("parse failed: " + type.getSimpleName(), e);
        }
    }
}
