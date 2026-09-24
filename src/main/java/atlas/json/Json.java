package atlas.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Json {
    public static final ObjectMapper M = new ObjectMapper();

    private Json() {}

    public static ObjectNode obj() { return M.createObjectNode(); }
    public static ArrayNode arr() { return M.createArrayNode(); }

    public static String write(JsonNode n) {
        try { return M.writeValueAsString(n); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static JsonNode read(String s) {
        try { return M.readTree(s); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static JsonNode tryRead(String s) {
        try { return M.readTree(s); }
        catch (Exception e) { return null; }
    }
}
