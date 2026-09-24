package spike.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable input material. The six mandated source classes are stored separately
 * by {@code kind}; adjudication events are their own kind and never share rows
 * with cluster labels.
 */
public record Material(
        String id,
        String kind,
        String version,
        String sourceRef,
        JsonNode raw,
        long receivedSeq
) {
    public static final String WAVEFORM_REF = "waveform_ref";
    public static final String GEOMETRY = "probe_geometry";
    public static final String DETECTION_THRESHOLD = "detection_threshold";
    public static final String FEATURE_VERSION = "feature_version";
    public static final String CLUSTER_LABELS = "cluster_labels";
    public static final String ADJUDICATION_EVENT = "adjudication_event";

    public static final java.util.List<String> BASELINE_KINDS = java.util.List.of(
            WAVEFORM_REF, GEOMETRY, DETECTION_THRESHOLD, FEATURE_VERSION, CLUSTER_LABELS, ADJUDICATION_EVENT);
}
