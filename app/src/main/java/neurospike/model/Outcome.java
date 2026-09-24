package neurospike.model;

/**
 * The three mandated failure classes are first-class outcomes, never collapsed
 * into a single error flag or an empty-result success.
 */
public enum Outcome {
    OK,
    FORMAT_CORRUPT,
    SEMANTIC_UNRESOLVABLE,
    BUDGET_EXHAUSTED
}
