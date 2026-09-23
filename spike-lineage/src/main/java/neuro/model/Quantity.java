package neuro.model;

import java.util.Objects;

/**
 * A domain quantity that always carries its raw representation and a unit / coordinate basis.
 * The raw representation is preserved verbatim on import; {@code value} is only populated when a
 * lossless conversion to the canonical internal unit exists. Values that cannot be converted stay
 * in place and carry a diagnostic instead of being dropped or faked.
 */
public final class Quantity {
    private final String raw;
    private final Double value;
    private final String unit;
    private final String basis;
    private final String validRange;
    private final String conversionDiagnostic;

    private Quantity(String raw, Double value, String unit, String basis, String validRange, String conversionDiagnostic) {
        this.raw = raw;
        this.value = value;
        this.unit = unit;
        this.basis = basis;
        this.validRange = validRange;
        this.conversionDiagnostic = conversionDiagnostic;
    }

    public static Quantity of(String raw, Double value, String unit, String basis, String validRange, String diagnostic) {
        Objects.requireNonNull(raw, "raw representation required");
        return new Quantity(raw, value, unit, basis, validRange, diagnostic);
    }

    public static Quantity converted(String raw, double value, String unit, String basis, String validRange) {
        return new Quantity(raw, value, unit, basis, validRange, null);
    }

    public static Quantity unconverted(String raw, String unit, String basis, String diagnostic) {
        return new Quantity(raw, null, unit, basis, null, diagnostic);
    }

    public boolean isConvertible() {
        return value != null;
    }

    public String raw() { return raw; }
    public Double value() { return value; }
    public String unit() { return unit; }
    public String basis() { return basis; }
    public String validRange() { return validRange; }
    public String conversionDiagnostic() { return conversionDiagnostic; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantity q)) return false;
        return raw.equals(q.raw) && Objects.equals(unit, q.unit) && Objects.equals(basis, q.basis);
    }
    @Override public int hashCode() { return Objects.hash(raw, unit, basis); }
    @Override public String toString() {
        return isConvertible() ? (value + " " + unit + " [raw=" + raw + "]") : (raw + " " + unit + " (unconverted)");
    }
}
