package eph.archive;

import java.util.Map;
import java.util.TreeMap;

/**
 * Located, typed diagnostic. Import/parse failures never collapse into null/default success:
 * every unconvertible value stays in place ({@code raw}) and carries one of these records.
 *
 * @param severity {@code SEMANTIC}/{@code FORMAT} (the whole claimed object cannot be built),
 *                 {@code BUDGET} (resource budget hit), {@code LOCAL} (the value is retained
 *                 as-is and this one derived result is invalid), {@code CONFLICT} (two legal
 *                 decisions disagree; both candidates are kept).
 * @param code     stable machine-readable code
 * @param location dotted path / table.row.field style position
 * @param message  human readable explanation
 */
public record Diagnostic(String severity, String code, String location, String message,
                         Map<String, String> context) {

    public Diagnostic {
        context = context == null ? Map.of() : Map.copyOf(new TreeMap<>(context));
    }

    public static Diagnostic semantic(String code, String location, String message) {
        return new Diagnostic("SEMANTIC", code, location, message, Map.of());
    }

    public static Diagnostic format(String code, String location, String message) {
        return new Diagnostic("FORMAT", code, location, message, Map.of());
    }

    public static Diagnostic budget(String code, String location, String message) {
        return new Diagnostic("BUDGET", code, location, message, Map.of());
    }

    public static Diagnostic error(String code, String location, String message) {
        return semantic(code, location, message);
    }

    public static Diagnostic local(String code, String location, String message) {
        return new Diagnostic("LOCAL", code, location, message, Map.of());
    }

    public static Diagnostic conflict(String location, String message, Map<String, String> context) {
        return new Diagnostic("CONFLICT", "CONFLICTING_DECISION", location, message, context);
    }

    public boolean isFatal() {
        return "ERROR".equals(severity);
    }
}
