package org.neuro.gsb.domain;

import java.util.Objects;

/**
 * A physical quantity that always retains its original representation.
 * Normalisation to a canonical value happens only in the derived layer.
 * A value that cannot be converted stays in place ({@link #value()} == null)
 * and carries a diagnostic instead of being replaced by a default.
 */
public record Measure(String raw, String unit, String datum, Double value, String diagnostic) {

    public Measure {
        Objects.requireNonNull(raw, "raw representation is required");
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit is required for a measure");
        }
    }

    /** A quantity with a canonical numeric value in a known unit / datum. */
    public static Measure of(double value, String unit, String datum) {
        return new Measure(fmt(value), unit, datum, value, null);
    }

    /** A quantity with a canonical value plus an explicit original spelling. */
    public static Measure of(String raw, double value, String unit, String datum) {
        return new Measure(raw, unit, datum, value, null);
    }

    /** A value that could not be converted: kept in place with a diagnostic. */
    public static Measure unconvertible(String raw, String unit, String diagnostic) {
        return new Measure(raw, unit, null, null, diagnostic);
    }

    public boolean isConvertible() {
        return value != null && diagnostic == null;
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        return Double.toString(v);
    }
}
