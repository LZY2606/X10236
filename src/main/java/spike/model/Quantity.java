package spike.model;

import java.util.Objects;

/**
 * A domain quantity that always carries a unit or coordinate/time basis.
 * Raw values are preserved on import (rawValue/rawUnit); the derived layer is the only place
 * that normalizes to the canonical unit. A value that cannot be converted stays at its origin
 * with a diagnostic instead of being silently dropped.
 */
public final class Quantity {
    private final double value;
    private final String unit;
    private final Double rawValue;
    private final String rawUnit;
    private final double validLow;
    private final double validHigh;
    private final boolean rangeKnown;

    private Quantity(double value, String unit, Double rawValue, String rawUnit,
                     double validLow, double validHigh, boolean rangeKnown) {
        this.value = value;
        this.unit = unit;
        this.rawValue = rawValue;
        this.rawUnit = rawUnit;
        this.validLow = validLow;
        this.validHigh = validHigh;
        this.rangeKnown = rangeKnown;
    }

    /** A value already expressed in the canonical unit. */
    public static Quantity of(double value, String unit) {
        return new Quantity(value, unit, null, null, 0, 0, false);
    }

    public static Quantity ofRaw(double rawValue, String rawUnit, double canonicalValue, String canonicalUnit) {
        return new Quantity(canonicalValue, canonicalUnit, rawValue, rawUnit, 0, 0, false);
    }

    public Quantity withValidRange(double low, double high) {
        return new Quantity(value, unit, rawValue, rawUnit, low, high, true);
    }

    public double value() {
        return value;
    }

    public String unit() {
        return unit;
    }

    public boolean converted() {
        return rawValue != null;
    }

    public Double rawValue() {
        return rawValue;
    }

    public String rawUnit() {
        return rawUnit;
    }

    public boolean rangeKnown() {
        return rangeKnown;
    }

    public boolean valid() {
        return !rangeKnown || (value >= validLow && value <= validHigh);
    }

    public String invalidReason() {
        if (!rangeKnown || valid()) {
            return null;
        }
        return "value " + value + " " + unit + " outside valid range [" + validLow + ", " + validHigh + "] " + unit;
    }

    @Override
    public String toString() {
        if (rawValue != null) {
            return rawValue + rawUnit + "(=" + value + unit + ")";
        }
        return value + unit;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Quantity q)) {
            return false;
        }
        return Double.compare(q.value, value) == 0 && Objects.equals(unit, q.unit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, unit);
    }
}
