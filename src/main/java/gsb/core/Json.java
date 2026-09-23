package gsb.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Canonical JSON helpers. Canonical form (sorted keys) feeds content hashing,
 *  so identical logical input always yields identical identities and order. */
public final class Json {
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final ObjectMapper PLAIN = new ObjectMapper();

    private Json() {}

    public static String canon(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize: " + e.getMessage(), e);
        }
    }

    public static String write(Object value) {
        try {
            return PLAIN.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize: " + e.getMessage(), e);
        }
    }

    public static JsonNode read(String json) {
        try {
            return PLAIN.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("bad json: " + e.getMessage(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return PLAIN.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("bad json: " + e.getMessage(), e);
        }
    }
}
