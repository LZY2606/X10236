package com.neurospike.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A positioned diagnostic. Incomplete or unconvertible input produces one of
 * these; we never substitute nulls/defaults and pretend success.
 */
public record Diagnostic(String stage, String position, String severity, String message) {
    public static Diagnostic error(String stage, String position, String message) {
        return new Diagnostic(stage, position, "ERROR", message);
    }

    public static Diagnostic warn(String stage, String position, String message) {
        return new Diagnostic(stage, position, "WARN", message);
    }

    public ObjectNode toJson() {
        ObjectNode n = Json.M.createObjectNode();
        n.put("stage", stage);
        n.put("position", position);
        n.put("severity", severity);
        n.put("message", message);
        return n;
    }
}
