package spikeline.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;

/**
 * Shared JSON/codec utilities. Canonical JSON (sorted keys, no pretty printing)
 * is the basis for all content-derived identifiers and input digests, so equal
 * inputs always produce equal ids and stable ordering.
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    public static final ObjectMapper CANONICAL = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_ABSENT)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperKindHack(), true);

    private Json() {
    }

    @SuppressWarnings("deprecation")
    private static boolean MapperKindHack() {
        // deterministic numeric output is default; kept as indirection for readability
        return true;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json encode failed", e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("json encode failed", e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("json decode failed: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("json decode failed: " + e.getOriginalMessage(), e);
        }
    }

    /** Canonical encoding used for identity/digests: keys sorted, compact. */
    public static String canonical(Object value) {
        try {
            Object normalized = MAPPER.convertValue(value, TreeMap.class) instanceof TreeMap ? value : value;
            JsonNode node = MAPPER.valueToTree(value);
            return CANONICAL.writeValueAsString(sort(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical json failed", e);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node == null || !node.isObject()) {
            return node;
        }
        var sorted = MAPPER.createObjectNode();
        var fields = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(fields::add);
        java.util.Collections.sort(fields);
        for (String field : fields) {
            JsonNode child = node.get(field);
            sorted.set(field, child.isObject() || child.isArray() ? sortDeep(child) : child);
        }
        return sorted;
    }

    private static JsonNode sortDeep(JsonNode node) {
        if (node.isObject()) {
            return sort(node);
        }
        if (node.isArray()) {
            var arr = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                arr.add(sortDeep(child));
            }
            return arr;
        }
        return node;
    }

    public static String digest(String canonicalJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Content-derived stable identifier: {@code prefix + ":" + 16 hex chars}. */
    public static String id(String prefix, Object identityBody) {
        return prefix + ":" + digest(canonical(identityBody));
    }
}
