package spike.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Structured evidence event. Refractory violations, amplitude drift and
 * multi-channel propagation are evidence only — they never reassign unit
 * identity by themselves. Machine-readable {@code type} drives replay.
 */
public record Evidence(
        String id,
        String type,
        String unitId,
        long firstSample,
        JsonNode detail,
        long producedSeq
) {
    public static final String REFRACTORY_VIOLATION = "refractory_violation";
    public static final String AMPLITUDE_DRIFT = "amplitude_drift";
    public static final String MULTI_CHANNEL_PROPAGATION = "multi_channel_propagation";
    public static final String CONCURRENT_SPIKES = "concurrent_spikes";
    public static final String SHARED_EVENT_AFTER_SPLIT = "shared_event_after_split";
    public static final String NUMBERING_CHANGE = "numbering_change";
    public static final String WINDOW_ENDPOINT = "window_endpoint";
}
