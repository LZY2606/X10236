package spikespec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** JSON helpers: canonical serialization and content digests for provenance. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper();
    public static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Json() {}

    public static ObjectNode obj() { return M.createObjectNode(); }
    public static ArrayNode arr() { return M.createArrayNode(); }

    public static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new JsonFormatException(e);
        }
    }

    /** Parse failure carrying byte/line/column position for diagnostics. */
    public static final class JsonFormatException extends RuntimeException {
        public final String position;
        JsonFormatException(Exception cause) {
            super(cause.getMessage(), cause);
            String pos = "unknown";
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException jpe
                    && jpe.getLocation() != null) {
                pos = "line " + jpe.getLocation().getLineNr()
                        + ", column " + jpe.getLocation().getColumnNr();
            }
            this.position = pos;
        }
    }

    /** Canonical JSON: object keys sorted recursively, arrays keep order. */
    public static String canonical(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(n, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode n, StringBuilder sb) {
        if (n == null || n.isNull()) { sb.append("null"); return; }
        if (n.isObject()) {
            sb.append('{');
            List<String> keys = new ArrayList<>();
            n.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                first = false;
                sb.append(M.valueToTree(k).toString()).append(':');
                writeCanonical(n.get(k), sb);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(n.get(i), sb);
            }
            sb.append(']');
        } else if (n.isNumber()) {
            // normalize numbers via BigDecimal textual form
            sb.append(n.decimalValue().stripTrailingZeros().toPlainString());
        } else {
            sb.append(n.toString());
        }
    }

    public static String digest(JsonNode n) {
        return sha256(canonical(n));
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
