package com.neurogsb.core;

import java.util.List;

/**
 * Import outcomes are three (plus success) mutually exclusive results:
 * resource budget exhausted, format corrupt, semantics unsolvable.
 * Never a silent partial success with null/default padding.
 */
public sealed interface ImportResult {
    record Ok(String importEventId, int eventsStored, List<String> order) implements ImportResult {}
    record BudgetExhausted(String budget, int limit, int required) implements ImportResult {}
    record FormatCorrupt(List<Diagnostic> diagnostics) implements ImportResult {}
    record SemanticsUnsolvable(List<Diagnostic> diagnostics) implements ImportResult {}

    default String kind() {
        if (this instanceof Ok) return "OK";
        if (this instanceof BudgetExhausted) return "BUDGET_EXHAUSTED";
        if (this instanceof FormatCorrupt) return "FORMAT_CORRUPT";
        return "SEMANTICS_UNSOLVABLE";
    }
}
