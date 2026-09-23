package ephys.model;

/** Three failure classes must stay distinct, plus success. */
public enum ImportStatus {
    OK,
    BUDGET_EXHAUSTED,
    FORMAT_CORRUPT,
    SEMANTIC_UNSOLVABLE
}
