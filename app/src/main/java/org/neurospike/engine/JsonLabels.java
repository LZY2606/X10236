package org.neurospike.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.neurospike.io.Json;

import java.util.Map;

final class JsonLabels {
    private JsonLabels() {
    }

    static void copyAssignments(String normalizedJson, Map<String, String> target) {
        JsonNode root = Json.read(normalizedJson, JsonNode.class);
        JsonNode assignments = root.path("assignments");
        assignments.fields().forEachRemaining(entry -> target.put(entry.getKey(), entry.getValue().asText()));
    }
}
