package spike.core;

/**
 * Three deliberately distinct terminal outcomes. They are never collapsed:
 * <ul>
 *   <li>COMPUTED — a value exists and is valid;</li>
 *   <li>BUDGET_EXHAUSTED — the compute budget ran out, input is fine, retry with more budget is meaningful;</li>
 *   <li>UNRESOLVED — the input is syntactically intact but semantically has no unique answer (conflict);</li>
 *   <li>CORRUPT — the input itself is malformed or required pieces are missing; no computation attempted.</li>
 * </ul>
 */
public enum Outcome {
    COMPUTED,
    BUDGET_EXHAUSTED,
    UNRESOLVED,
    CORRUPT
}
