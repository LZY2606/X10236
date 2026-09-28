package com.neuro.gsb.domain;

/** A positioned diagnostic. Never silently defaults: the value stays where it was. */
public record Diagnostic(Severity severity, String position, String message) {
    public enum Severity { INFO, WARNING, ERROR }

    public static Diagnostic info(String position, String message) {
        return new Diagnostic(Severity.INFO, position, message);
    }
    public static Diagnostic warning(String position, String message) {
        return new Diagnostic(Severity.WARNING, position, message);
    }
    public static Diagnostic error(String position, String message) {
        return new Diagnostic(Severity.ERROR, position, message);
    }
}
