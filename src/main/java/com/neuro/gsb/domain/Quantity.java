package com.neuro.gsb.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * A domain quantity that always carries its unit and coordinate/time basis.
 * The raw representation (as imported) is preserved; normalization happens
 * only in the derived layer. Values that cannot be converted stay in place
 * and produce a Diagnostic instead of being silently dropped or defaulted.
 */
public final class Quantity {
    public enum Basis {
        RECORDING_CLOCK,   // time relative to recording start
        PROBE_COORDINATE,  // µm on the probe layout
        SAMPLE_INDEX,      // raw sample index, needs sampling rate to convert
        NONE
    }

    private final String raw;        // original representation, never rewritten
    private final String unit;       // e.g. "ms", "samples", "uV", "ADC", "um"
    private final Basis basis;

    public Quantity(String raw, String unit, Basis basis) {
        this.raw = Objects.requireNonNull(raw, "raw");
        this.unit = unit == null ? "" : unit;
        this.basis = basis == null ? Basis.NONE : basis;
    }

    public String raw() { return raw; }
    public String unit() { return unit; }
    public Basis basis() { return basis; }

    public Optional<Double> numeric() {
        try {
            return Optional.of(Double.parseDouble(raw.trim()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Canonical time in ms. Samples require a sampling rate (kHz -> samples per ms). */
    public Optional<Double> toMillis(Double samplingRateKHz) {
        Optional<Double> v = numeric();
        if (v.isEmpty()) return Optional.empty();
        return switch (unit) {
            case "ms" -> v;
            case "s" -> Optional.of(v.get() * 1000.0);
            case "samples" -> samplingRateKHz == null
                    ? Optional.empty()
                    : Optional.of(v.get() / samplingRateKHz);
            default -> Optional.empty();
        };
    }

    /** Canonical amplitude in µV. ADC counts require a gain (µV per count). */
    public Optional<Double> toMicroVolts(Double uvPerCount) {
        Optional<Double> v = numeric();
        if (v.isEmpty()) return Optional.empty();
        return switch (unit) {
            case "uV" -> v;
            case "mV" -> Optional.of(v.get() * 1000.0);
            case "ADC" -> uvPerCount == null
                    ? Optional.empty()
                    : Optional.of(v.get() * uvPerCount);
            default -> Optional.empty();
        };
    }

    /** Canonical position in µm on the probe coordinate basis. */
    public Optional<Double> toMicrons() {
        Optional<Double> v = numeric();
        if (v.isEmpty()) return Optional.empty();
        return switch (unit) {
            case "um" -> v;
            case "mm" -> Optional.of(v.get() * 1000.0);
            default -> Optional.empty();
        };
    }

    @Override public String toString() { return raw + " " + unit; }
}
