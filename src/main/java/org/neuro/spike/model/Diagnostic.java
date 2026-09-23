package org.neuro.spike.model;

/** A positional, machine-readable explanation of why a value/result is not usable. */
public record Diagnostic(String location, String code, String message) {
    public static Diagnostic of(String location, String code, String message) {
        return new Diagnostic(location, code, message);
    }
}
