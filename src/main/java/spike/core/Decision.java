package spike.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A mutable-but-versioned curator decision (accept/reject/parameter change).
 * Decisions are append-only; walking a branch up to a seq reconstructs the
 * visible conclusions at that point in history.
 */
public record Decision(
        String id,
        String branchId,
        long seq,
        String kind,
        String subjectType,
        String subjectId,
        JsonNode payload,
        String paramsVersion,
        String rationale,
        String basedOnResultId,
        String forkedToBranch,
        long opSeq
) {
    public static final String ACCEPT = "accept";
    public static final String REJECT = "reject";
    public static final String PARAM_ADJUST = "param_adjust";
    public static final String WINDOW_CHANGE = "window_change";
}
