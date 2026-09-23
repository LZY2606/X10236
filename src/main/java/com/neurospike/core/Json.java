package com.neurospike.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** JSON helpers: canonical (key-sorted) serialization and content digests. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper();

    static {
        M.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    private Json() {}

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    public static JsonNode read(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("json parse failed: " + e.getMessage(), e);
        }
    }

    /** Canonical serialization: object keys sorted, so equal content yields equal bytes. */
    public static String canonical(JsonNode n) {
        return write(n);
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String digest(JsonNode canonicalContent) {
        return sha256(canonical(canonicalContent));
    }

    public static String shortDigest(JsonNode canonicalContent) {
        return digest(canonicalContent).substring(0, 16);
    }
}
