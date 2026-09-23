package org.neurospike.domain;

import java.util.Map;

/**
 * Positioned diagnostic. Unconvertible values stay at their original place and carry this;
 * nulls/defaults are never used to fake success.
 */
public record Diagnostic(String code, String file, int line, int column, String message,
                         String rawValue, Map<String, Object> context) {
    public static final String CORRUPT_FORMAT = "CORRUPT_FORMAT";
    public static final String SEMANTIC_UNRESOLVABLE = "SEMANTIC_UNRESOLVABLE";
    public static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    public static final String UNCONVERTIBLE_VALUE = "UNCONVERTIBLE_VALUE";
    public static final String LOCAL_FAILURE = "LOCAL_FAILURE";
    public static final String REF_OUT_OF_RANGE = "REF_OUT_OF_RANGE";

    public static Diagnostic of(String code, String file, int line, int column, String message,
                                String rawValue, Map<String, Object> context) {
        return new Diagnostic(code, file, line, column, message, rawValue, context);
    }
}
