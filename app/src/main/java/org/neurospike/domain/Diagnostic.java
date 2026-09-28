package org.neurospike.domain;

public record Diagnostic(String code, String location, String message, String severity) {
    public static Diagnostic error(String code, String location, String message) {
        return new Diagnostic(code, location, message, "ERROR");
    }

    public static Diagnostic warning(String code, String location, String message) {
        return new Diagnostic(code, location, message, "WARNING");
    }
}
