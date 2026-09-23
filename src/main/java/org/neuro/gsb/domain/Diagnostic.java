package org.neuro.gsb.domain;

/** A positional, machine-readable diagnostic. Never used to fake success. */
public record Diagnostic(String code, String severity, String path, String message) {

    public static final String ERROR = "ERROR";
    public static final String WARN = "WARN";

    public static Diagnostic error(String code, String path, String message) {
        return new Diagnostic(code, ERROR, path, message);
    }

    public static Diagnostic warn(String code, String path, String message) {
        return new Diagnostic(code, WARN, path, message);
    }
}
