package neurospike.model;

import java.util.List;

/**
 * Import results: three failure modes are distinct types, never conflated.
 *  - ResourceBudgetExhausted: budget (e.g. max records) ran out.
 *  - FormatCorrupt: bytes/JSON could not be parsed at all.
 *  - SemanticUnsolvable: parsed fine, but constraints cannot be satisfied.
 */
public sealed interface ImportOutcome {
    record Ok(int imported, List<Diag> diagnostics) implements ImportOutcome {}
    record ResourceBudgetExhausted(String detail, int limit) implements ImportOutcome {}
    record FormatCorrupt(String detail, String position) implements ImportOutcome {}
    record SemanticUnsolvable(String detail, List<Diag> diagnostics) implements ImportOutcome {}

    default boolean isOk() { return this instanceof Ok; }
}
