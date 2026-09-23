package org.neuro.gsb.domain;

/** A single scalar/quantity finding inside an evidence result, with its own unit and valid range. */
public record EvidenceValue(String key, double value, String unit, String validRange, String note) {
}
