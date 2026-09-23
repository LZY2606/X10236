package neuro.model;

import java.util.List;

/**
 * A located diagnostic. Inputs that are incomplete or semantically impossible produce one of these
 * instead of a successful result backed by nulls or defaults.
 *
 * <p>The three distinct failure surfaces map to {@link Severity} plus {@link #code()}:
 * <ul>
 *   <li>{@code BUDGET_EXHAUSTED} - a resource budget (event count / scan cost) was exhausted;</li>
 *   <li>{@code FORMAT_CORRUPT} - the bytes / fields are structurally broken;</li>
 *   <li>{@code SEMANTIC_IMPOSSIBLE} - well formed but no legal interpretation exists.</li>
 * </ul>
 */
public record Diagnostic(String code, Severity severity, String message, List<Location> path) {

    public enum Severity { ERROR, LOCAL_FAILURE, WARNING }

    public static Diagnostic of(String code, Severity severity, String message, List<Location> path) {
        return new Diagnostic(code, severity, message, List.copyOf(path));
    }

    public static Diagnostic error(String code, String message, Location... path) {
        return new Diagnostic(code, Severity.ERROR, message, List.of(path));
    }

    public static Diagnostic local(String code, String message, Location... path) {
        return new Diagnostic(code, Severity.LOCAL_FAILURE, message, List.of(path));
    }

    public static Diagnostic warning(String code, String message, Location... path) {
        return new Diagnostic(code, Severity.WARNING, message, List.of(path));
    }

    public record Location(String file, String field, Integer index) {
        public static Location of(String file, String field, Integer index) {
            return new Location(file, field, index);
        }
        public static Location field(String file, String field) {
            return new Location(file, field, null);
        }
        @Override public String toString() {
            return file + (field != null ? "#" + field : "") + (index != null ? "[" + index + "]" : "");
        }
    }
}
