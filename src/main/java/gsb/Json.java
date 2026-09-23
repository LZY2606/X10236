package gsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** JSON helpers: canonical serialization and content digests. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed: " + e.getMessage(), e);
        }
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Canonical JSON: object keys sorted recursively so equivalent input yields identical bytes. */
    public static String canonical(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append('"').append(':');
                writeCanonical(e.getValue(), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(node.get(i), sb);
            }
            sb.append(']');
        } else if (node.isNumber()) {
            // normalize numeric representation (1.0 vs 1.00)
            sb.append(node.decimalValue().stripTrailingZeros().toPlainString());
        } else if (node.isTextual()) {
            sb.append(MAPPER.getNodeFactory().textNode(node.asText()).toString());
        } else {
            sb.append(node.toString());
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String digestOf(JsonNode node) {
        return sha256(canonical(node));
    }
}
