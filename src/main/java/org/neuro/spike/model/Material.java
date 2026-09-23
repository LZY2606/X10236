package org.neuro.spike.model;

import java.util.List;

/**
 * An immutable source artifact. {@code payloadJson} holds the domain fields
 * (references, coordinates, thresholds, timestamps...); {@code unit} and
 * {@code frame} annotate the measurement and coordinate/time datum.
 */
public record Material(
        String uid,
        MaterialType type,
        String payloadJson,
        String unit,
        String frame,
        List<Diagnostic> diagnostics,
        int seq) {

    public boolean usable() {
        return diagnostics == null || diagnostics.isEmpty();
    }
}
