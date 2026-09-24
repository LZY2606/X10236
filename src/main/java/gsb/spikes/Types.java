package gsb.spikes;

import java.util.List;

public final class Types {
    private Types() {}

    /** A located diagnostic: never silently drop or default-fill a bad value. */
    public record Diagnostic(String position, String message, String rawValue, String unit) {
        public String toLine() {
            return position + ": " + message
                    + (rawValue == null ? "" : " [raw=" + rawValue + "]")
                    + (unit == null ? "" : " [unit=" + unit + "]");
        }
    }

    /**
     * Three failure families must stay distinguishable:
     * resource budget exhausted, corrupt format, semantically unsolvable.
     */
    public sealed interface Result<T> {
        record Ok<T>(T value) implements Result<T> {}
        record BudgetExhausted<T>(String scope, long used, long limit) implements Result<T> {}
        record FormatCorrupt<T>(List<Diagnostic> diagnostics) implements Result<T> {}
        record SemanticUnsolvable<T>(String reason, List<Diagnostic> diagnostics) implements Result<T> {}

        default boolean isOk() { return this instanceof Ok<T>; }
    }

    /** Half-open time window [fromMs, toMs). Endpoints are deterministic. */
    public record TimeWindow(double fromMs, double toMs) {
        public boolean contains(double tMs) { return tMs >= fromMs && tMs < toMs; }
        public boolean intersects(TimeWindow o) { return fromMs < o.toMs && o.fromMs < toMs; }
        public String label() { return "[" + fromMs + ", " + toMs + ") ms"; }
    }

    /** A normalized quantity keeps its raw representation alongside. */
    public record Quantity(double value, String unit, String raw, boolean converted, Diagnostic diagnostic) {
        public static Quantity raw(String raw, String unit, Diagnostic d) {
            return new Quantity(Double.NaN, unit, raw, false, d);
        }
        public static Quantity of(double value, String unit, String raw) {
            return new Quantity(value, unit, raw, true, null);
        }
    }

    public enum EvidenceKind { REFRACTORY_VIOLATION, AMPLITUDE_DRIFT, MULTICHANNEL_PROPAGATION, CONCURRENT_SPIKE }

    public enum AdjudicationType { ACCEPT, REJECT, MERGE, SPLIT, PARAM_ADJUST, RESOLVE_CONFLICT }

    public enum ComputationStatus { VALID, INVALIDATED, UNDECIDED }
}
