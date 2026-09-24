package neurospike;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Append-only event. Every import, parameter revision, source change and human
 * decision gets one. Identity (eventId) is a causal hash over seq+parent+kind+
 * payload+material versions, never a mutable row id. Wall-clock time is recorded
 * for humans but excluded from the id so equivalent replays get identical ids.
 */
public record Event(String eventId, long seq, String kind, String parentEventId,
                    JsonNode payload, String inputDigest, String paramVersion,
                    String status, String diagnosticJson, String createdAt) {

    public static final String BASELINE_IMPORT = "BASELINE_IMPORT";
    public static final String PARAM_REVISION = "PARAM_REVISION";
    public static final String SOURCE_CHANGE = "SOURCE_CHANGE";
    public static final String DECISION = "DECISION";
}
