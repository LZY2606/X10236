package eph.archive;

import java.util.List;

/**
 * Three-way result envelope required by the domain:
 * <ul>
 *   <li>{@code OK} - fully parsed/evaluated,</li>
 *   <li>{@code REJECTED} - format corrupt / semantic unresolvable; positioned diagnostics only,
 *       no partial materialisation of the claimed object,</li>
 *   <li>{@code BUDGET_EXHAUSTED} - resource budget hit; distinct from a parse failure,</li>
 *   <li>{@code LOCAL_FAILURE} - the object exists; some derived cells are invalid and stay raw.</li>
 * </ul>
 */
public enum Outcome {
    OK, LOCAL_FAILURE, REJECTED, BUDGET_EXHAUSTED;

    public static Outcome from(List<Diagnostic> diagnostics) {
        boolean local = false;
        for (Diagnostic d : diagnostics) {
            if ("ERROR".equals(d.severity())) {
                return REJECTED;
            }
            if ("BUDGET".equals(d.severity())) {
                return BUDGET_EXHAUSTED;
            }
            if ("LOCAL".equals(d.severity()) || "CONFLICT".equals(d.severity())) {
                local = true;
            }
        }
        return local ? LOCAL_FAILURE : OK;
    }
}
