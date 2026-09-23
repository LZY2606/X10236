package gsb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class Json {
    public static final ObjectMapper M = new ObjectMapper();
    public static final ObjectMapper CANON = new ObjectMapper();
    static {
        CANON.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        CANON.configure(SerializationFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    private Json() {}

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canon(Object o) {
        try {
            return CANON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> map(String s) {
        try {
            return M.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            throw new Model.FormatCorrupt("body", "不是合法 JSON: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object o) {
        return M.convertValue(o, Map.class);
    }

    public static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String id(String prefix, Object... parts) {
        return prefix + "-" + sha(canon(List.of(parts))).substring(0, 16);
    }
}
