package org.neuro.spike.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A physical value with its raw representation preserved verbatim.
 * Canonicalisation happens only in the derivation layer. A value that
 * cannot be converted stays at its origin with a diagnostic rather than
 * being dropped for plotting.
 */
public final class Quantity {
    private final String raw;
    private final Double siValue;
    private final String unit;
    private final String diagnostic;

    private Quantity(String raw, Double siValue, String unit, String diagnostic) {
        this.raw = raw;
        this.siValue = siValue;
        this.unit = unit;
        this.diagnostic = diagnostic;
    }

    public static Quantity parsed(String raw, double siValue, String unit) {
        return new Quantity(raw, siValue, unit, null);
    }

    public static Quantity unconvertible(String raw, String reason) {
        return new Quantity(raw, null, null, reason);
    }

    public String raw() { return raw; }
    public Optional<Double> si() { return Optional.ofNullable(siValue); }
    public String unit() { return unit; }
    public String diagnostic() { return diagnostic; }
    public boolean convertible() { return siValue != null; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantity q)) return false;
        return Objects.equals(raw, q.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(raw);
    }

    @Override
    public String toString() {
        return convertible() ? raw + " (" + siValue + " " + unit + ")" : raw + " [!]";
    }
}
