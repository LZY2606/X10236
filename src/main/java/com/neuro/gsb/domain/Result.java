package com.neuro.gsb.domain;

import java.util.List;

/**
 * Outcome of an import / computation. The three failure modes are distinct
 * and must never be collapsed into one another:
 *  - BUDGET_EXHAUSTED: resource budget ran out (well-formed input, too much of it)
 *  - FORMAT_CORRUPT:   bytes/records do not parse
 *  - SEMANTIC_UNSOLVABLE: parses fine, but no unique legal resolution exists
 */
public sealed interface Result<T> {
    record Ok<T>(T value, List<Diagnostic> diagnostics) implements Result<T> {}
    record BudgetExhausted<T>(String position, String detail, List<Diagnostic> diagnostics) implements Result<T> {}
    record FormatCorrupt<T>(String position, String detail) implements Result<T> {}
    record SemanticUnsolvable<T>(String detail, List<String> legalCandidates, List<Diagnostic> diagnostics) implements Result<T> {}

    static <T> Result<T> ok(T value) { return new Ok<>(value, List.of()); }
    static <T> Result<T> ok(T value, List<Diagnostic> diags) { return new Ok<>(value, diags); }

    default boolean isOk() { return this instanceof Ok<T>; }
}
