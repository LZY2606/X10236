package eph.archive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Central JSON codecs. All payload hashes are computed on canonical serialisation. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> readMap(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<Double> readDoubles(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<Double>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String snippets(List<Double> values) {
        return write(values == null ? List.of() : values);
    }
}
