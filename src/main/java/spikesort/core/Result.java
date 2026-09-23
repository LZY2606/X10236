package spikesort.core;

import java.util.List;

/**
 * Outcome of an import / compute step. Resource-budget exhaustion, corrupt input
 * format and semantic unsolvability are three DISTINCT results - never collapsed
 * into a generic failure, and never faked into success with nulls or defaults.
 */
public sealed interface Result<T> {

    record Diagnostic(String stage, String location, String severity, String message, String raw) {}

    record Ok<T>(T value, List<Diagnostic> diagnostics) implements Result<T> {}

    /** Resource budget (max events / waveforms / rows) was exhausted. */
    record BudgetExceeded<T>(String stage, long limit, long attempted) implements Result<T> {}

    /** Input bytes/structure are corrupt (bad JSON, missing sections, bad header). */
    record CorruptFormat<T>(String location, String detail) implements Result<T> {}

    /** Input parses but cannot be resolved (dangling refs, empty window, empty split). */
    record SemanticUnsolvable<T>(String detail, List<String> candidates) implements Result<T> {}

    default boolean isOk() { return this instanceof Ok<T>; }
}
