package neurospike;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Shared Jackson mapper. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final JsonNodeFactory NODE = JsonNodeFactory.instance;

    private Json() {
    }
}
