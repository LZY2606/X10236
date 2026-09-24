package ephys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Canonical JSON + hashing helpers. Canonical form sorts object keys and
 *  normalizes numbers so equivalent content hashes identically. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper();

    private Json() {}

    public static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
        }
    }

    public static String write(JsonNode n) {
        try {
            return M.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        writeCanon(n, sb);
        return sb.toString();
    }

    private static void writeCanon(JsonNode n, StringBuilder sb) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            sb.append("null");
        } else if (n.isObject()) {
            sb.append('{');
            List<String> keys = new ArrayList<>();
            n.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(k)).append(':');
                writeCanon(n.get(k), sb);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanon(n.get(i), sb);
            }
            sb.append(']');
        } else if (n.isTextual()) {
            sb.append(quote(n.textValue()));
        } else if (n.isNumber()) {
            sb.append(n.decimalValue().stripTrailingZeros().toPlainString());
        } else if (n.isBoolean()) {
            sb.append(n.booleanValue());
        } else {
            sb.append(quote(n.asText()));
        }
    }

    private static String quote(String s) {
        try {
            return M.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortHash(String s) {
        return sha256(s).substring(0, 12);
    }
}
