package org.neuro.spike.model;

/**
 * Layers of the provenance archive. Immutable material kinds are kept
 * physically separate from mutable decisions and derived results so that
 * an adjudication can never silently overwrite a raw observation.
 */
public enum MaterialType {
    RECORDING_BASE(true),
    PROBE_GEOMETRY(true),
    WAVEFORM_REF(true),
    DETECTION_EVENT(true),
    DETECTION_THRESHOLD(true),
    TIME_WINDOW(true),
    CORRUPT_SEGMENT(true);

    private final boolean immutable;

    MaterialType(boolean immutable) {
        this.immutable = immutable;
    }

    public boolean immutable() {
        return immutable;
    }
}
