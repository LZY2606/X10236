package gsb.model;

/** The three failure outcomes are distinct results, never collapsed:
 *  resource budget exhausted, format corrupt, semantic unsolvable. */
public final class GsbException extends RuntimeException {
    public enum Kind { BUDGET_EXHAUSTED, FORMAT_CORRUPT, SEMANTIC_UNSOLVABLE }
    public final Kind kind;
    public final String position; // may be null

    public GsbException(Kind kind, String message) { this(kind, message, null); }
    public GsbException(Kind kind, String message, String position) {
        super(message);
        this.kind = kind;
        this.position = position;
    }
}
