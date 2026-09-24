package neuro.spike.provenance;

/**
 * A located, structured problem. Never represented as null or a default value:
 * incomplete input yields diagnostics that point at the offending material.
 *
 * Kind distinguishes the three structurally different failure outcomes:
 *  - CORRUPT        : bytes/format cannot be parsed at all
 *  - SEMANTIC       : parseable but meaningless or contradictory
 *  - BUDGET_EXHAUSTED: a declared resource budget ran out before completion
 */
public record Diagnostic(Kind kind, String code, String path, String message) {

    public enum Kind { CORRUPT, SEMANTIC, BUDGET_EXHAUSTED }

    public static Diagnostic corrupt(String path, String message) {
        return new Diagnostic(Kind.CORRUPT, "E_FORMAT", path, message);
    }

    public static Diagnostic semantic(String path, String message) {
        return new Diagnostic(Kind.SEMANTIC, "E_SEMANTIC", path, message);
    }

    public static Diagnostic budget(String path, String message) {
        return new Diagnostic(Kind.BUDGET_EXHAUSTED, "E_BUDGET", path, message);
    }

    public String toLine() { return kind + " " + code + " @ " + path + ": " + message; }
}
