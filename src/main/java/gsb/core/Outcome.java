package gsb.core;

/** The three failure modes are distinct outcomes, never collapsed into one
 *  generic error and never masked by null/default "success". */
public enum Outcome {
    OK,
    BUDGET_EXHAUSTED,     // resource budget ran out before the input was consumed
    FORMAT_CORRUPT,       // bytes/records cannot be parsed at all
    SEMANTIC_UNSOLVABLE   // parsed, but cannot be interpreted (unknown unit, no clock basis, ...)
}
