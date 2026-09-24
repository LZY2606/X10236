package neurospike;

import java.util.List;

/**
 * Result of reading an offline case pack. These three failures are intentionally
 * distinct types - never an empty/default success:
 *   BUDGET_EXHAUSTED  -> resource limit hit before semantic work
 *   FORMAT_CORRUPT    -> not parseable / structurally malformed
 *   SEMANTIC_UNRESOLVABLE -> parseable but meaningless without a decision
 */
public sealed interface ImportOutcome {

    record Loaded(CasePack pack, List<Diagnostic> warnings) implements ImportOutcome {
    }

    record BudgetExhausted(Diagnostic diagnostic) implements ImportOutcome {
    }

    record FormatCorrupt(List<Diagnostic> diagnostics) implements ImportOutcome {
    }

    record SemanticUnresolvable(List<Diagnostic> diagnostics) implements ImportOutcome {
    }
}
