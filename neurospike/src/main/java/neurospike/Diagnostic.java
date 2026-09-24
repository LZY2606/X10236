package neurospike;

/** A located diagnostic. {@code pointer} is a JSON pointer into the imported document. */
public record Diagnostic(String code, String pointer, String message, String originalValue) {

    public static final String FORMAT_CORRUPT = "FORMAT_CORRUPT";
    public static final String SEMANTIC_UNRESOLVABLE = "SEMANTIC_UNRESOLVABLE";
    public static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    public static final String LOCAL_VALUE_INVALID = "LOCAL_VALUE_INVALID";
    public static final String PARTIAL_WRITE = "PARTIAL_WRITE";

    @Override
    public String toString() {
        return code + " at " + pointer + ": " + message
                + (originalValue != null ? " [" + originalValue + "]" : "");
    }
}
