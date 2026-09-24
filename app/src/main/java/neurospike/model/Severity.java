package neurospike.model;

public enum Severity {
    /** Input cannot be parsed or fails structural validation. */
    FORMAT_CORRUPT,
    /** Input parses but no legal interpretation exists (e.g. impossible geometry). */
    SEMANTIC_UNRESOLVABLE,
    /** One item is unusable; the rest of the batch is still importable. */
    LOCAL_INVALID
}
