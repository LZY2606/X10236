package org.neurospike.caseflow.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class JsonCanonical {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCanonical() {
    }

    public static String canonical(JsonNode node) {
        StringBuilder builder = new StringBuilder();
        write(node, builder);
        return builder.toString();
    }

    private static void write(JsonNode node, StringBuilder builder) {
        if (node == null || node.isNull()) {
            builder.append("null");
        } else if (node.isObject()) {
            builder.append('{');
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
            while (iterator.hasNext()) {
                fields.add(iterator.next());
            }
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : fields) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append('"').append(field.getKey()).append('"').append(':');
                write(field.getValue(), builder);
            }
            builder.append('}');
        } else if (node.isArray()) {
            builder.append('[');
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                write(node.get(index), builder);
            }
            builder.append(']');
        } else {
            builder.append(node.toString());
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }
}
