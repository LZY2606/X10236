package com.neurospike.core;

import java.util.List;

/**
 * Result of an operation. Resource-budget exhaustion, corrupt input format and
 * semantic unsolvability are three DISTINCT outcomes, never collapsed into a
 * generic failure and never masked by fake success values.
 */
public sealed interface Outcome<T> {
    record Ok<T>(T value) implements Outcome<T> {}
    record BudgetExhausted<T>(String detail) implements Outcome<T> {}
    record CorruptFormat<T>(List<Diagnostic> diagnostics) implements Outcome<T> {}
    record Unsolvable<T>(List<Diagnostic> diagnostics) implements Outcome<T> {}

    default boolean isOk() {
        return this instanceof Ok<T>;
    }
}
