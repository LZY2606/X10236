package neurospike;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A mutable-layer human decision. Decisions never overwrite material; they
 * append. UNRESOLVED_CONFLICT keeps every legal candidate instead of guessing.
 */
public record Decision(String eventId, long seq, String targetId, String targetKind,
                       String verdict, String rationale, String reviewer,
                       List<String> candidates, String status, JsonNode params,
                       String supersedesEventId) {

    public static final String ACCEPT = "ACCEPT";
    public static final String REJECT = "REJECT";
    public static final String PARAM_ADJUST = "PARAM_ADJUST";
    public static final String SPLIT = "SPLIT";
    public static final String MERGE = "MERGE";
    public static final String RESOLVED = "RESOLVED";
    public static final String UNRESOLVED_CONFLICT = "UNRESOLVED_CONFLICT";
}
