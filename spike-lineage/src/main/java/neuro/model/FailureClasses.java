package neuro.model;

/** Marker constants so the three failure surfaces cannot be confused at call sites or in storage. */
public final class FailureClasses {
    private FailureClasses() {}

    /** A bounded resource budget was exhausted; retrying with a larger budget is a distinct outcome. */
    public static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    /** The bytes / fields are structurally corrupt; parsing cannot continue. */
    public static final String FORMAT_CORRUPT = "FORMAT_CORRUPT";
    /** Structurally valid but semantically impossible; no legal interpretation exists. */
    public static final String SEMANTIC_IMPOSSIBLE = "SEMANTIC_IMPOSSIBLE";
}
