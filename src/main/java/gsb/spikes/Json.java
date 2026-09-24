package gsb.spikes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Canonical JSON + content hashing. Equivalent inputs must hash identically. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    public static String canonical(Object value) {
        try {
            JsonNode tree = MAPPER.valueToTree(value);
            return MAPPER.writeValueAsString(tree);
        } catch (Exception e) {
            throw new IllegalStateException("canonical json failed", e);
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Content-addressed id: kind prefix + first 24 hex chars of canonical hash. */
    public static String contentId(String kind, Object content) {
        return kind + "-" + sha256(canonical(content)).substring(0, 24);
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    public static String write(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pretty(Object v) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
