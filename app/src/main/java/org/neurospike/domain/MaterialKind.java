package org.neurospike.domain;

public enum MaterialKind {
    WAVEFORM_REFERENCE("waveform_reference"),
    PROBE_GEOMETRY("probe_geometry"),
    DETECTION_THRESHOLD("detection_threshold"),
    FEATURE_VERSION("feature_version"),
    CLUSTER_LABEL("cluster_label"),
    ADJUDICATION_EVENT("adjudication_event");

    private final String table;

    MaterialKind(String table) {
        this.table = table;
    }

    public String table() {
        return table;
    }
}
