package spike.model;

/**
 * A positioned, machine-readable diagnostic. Incomplete/invalid input never becomes a null or a
 * silent default: it yields one of these. {@link Kind} keeps the three mandated failure classes
 * apart (corrupt format / semantic impossibility / resource budget exhausted).
 */
public record Diagnostic(Kind kind, String code, String location, String message) {

    public enum Kind {
        FORMAT_CORRUPT,
        SEMANTIC_IMPOSSIBLE,
        BUDGET_EXHAUSTED,
        LOCAL_FAILURE,
        UNCONVERTIBLE
    }

    public static Diagnostic corrupt(String code, String location, String message) {
        return new Diagnostic(Kind.FORMAT_CORRUPT, code, location, message);
    }

    public static Diagnostic semantic(String code, String location, String message) {
        return new Diagnostic(Kind.SEMANTIC_IMPOSSIBLE, code, location, message);
    }

    public static Diagnostic budget(String code, String location, String message) {
        return new Diagnostic(Kind.BUDGET_EXHAUSTED, code, location, message);
    }

    public static Diagnostic local(String code, String location, String message) {
        return new Diagnostic(Kind.LOCAL_FAILURE, code, location, message);
    }

    public String toMachineEvent() {
        return kind + " " + code + " at " + location + ": " + message;
    }
}
