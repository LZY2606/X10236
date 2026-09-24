package neurogsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON helpers: deterministic ordering + content digests. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper();

    private Json() {}

    public static ObjectNode obj() { return M.createObjectNode(); }
    public static ArrayNode arr() { return M.createArrayNode(); }

    public static JsonNode parse(String s) {
        try { return M.readTree(s); }
        catch (Exception e) { throw new JsonFormatException(e.getMessage(), e); }
    }

    /** Thrown when input bytes are not well-formed JSON -> FORMAT_CORRUPT. */
    public static final class JsonFormatException extends RuntimeException {
        public JsonFormatException(String msg, Throwable cause) { super(msg, cause); }
    }

    /** Canonical serialization: object keys sorted, arrays kept in order. */
    public static String canonical(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(n, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode n, StringBuilder sb) {
        if (n == null || n.isNull()) { sb.append("null"); return; }
        if (n.isObject()) {
            sb.append('{');
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) { Map.Entry<String, JsonNode> e = it.next(); sorted.put(e.getKey(), e.getValue()); }
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                writeCanonical(e.getValue(), sb);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) { if (i > 0) sb.append(','); writeCanonical(n.get(i), sb); }
            sb.append(']');
        } else if (n.isNumber()) {
            sb.append(n.numberValue().toString());
        } else if (n.isTextual()) {
            sb.append('"').append(n.textValue().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else if (n.isBoolean()) {
            sb.append(n.booleanValue());
        } else {
            sb.append(n.toString());
        }
    }

    public static String digest(JsonNode n) { return sha256(canonical(n)); }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Sort an array of objects by canonical form for deterministic insertion order. */
    public static List<JsonNode> sortedCanonically(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        list.sort(Comparator.comparing(Json::canonical));
        return list;
    }
}
