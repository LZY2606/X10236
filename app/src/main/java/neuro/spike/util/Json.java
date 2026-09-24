package neuro.spike.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Shared Jackson configuration: pretty, deterministic order, no JDK8 module needed.
 */
public final class Json {
    private Json() {
    }

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
}
