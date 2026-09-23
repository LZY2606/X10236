package spikesort.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;

/** JSON helpers. Canonical form sorts map keys so content hashes are stable. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new JsonException(e.getMessage());
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new JsonException(e.getMessage());
        }
    }

    /** Deterministic serialization used for content hashing and export. */
    public static String canonical(Object value) {
        try {
            Object tree = value instanceof JsonNode node
                    ? MAPPER.treeToValue(node, Object.class)
                    : value;
            return CANONICAL.writeValueAsString(tree);
        } catch (Exception e) {
            throw new JsonException(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode node) {
        return MAPPER.convertValue(node, Map.class);
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }
}
