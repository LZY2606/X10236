package neuro.spike.model;

import java.util.Objects;

/**
 * Immutable measurement preserving the representation used at import.
 * The raw text is retained so a value that cannot be canonicalised is never
 * silently dropped for the sake of a plot.
 */
public final class Quantity {
    public final double value;
    public final Unit unit;
    public final String raw;

    public Quantity(double value, Unit unit) {
        this(value, unit, format(value, unit));
    }

    public Quantity(double value, Unit unit, String raw) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("non-finite quantity: " + raw);
        }
        this.value = value;
        this.unit = Objects.requireNonNull(unit);
        this.raw = raw == null ? format(value, unit) : raw;
    }

    public Quantity(String raw, Unit unit) {
        this(parse(raw, unit), unit, raw == null ? "" : raw.trim());
    }

    private static double parse(String raw, Unit unit) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("empty quantity for unit " + unit.symbol);
        }
        String t = raw.trim().replace(",", "");
        try {
            double v = Double.parseDouble(t);
            if (Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException(raw);
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("unparseable quantity '" + raw + "' (" + unit.symbol + ")");
        }
    }

    /** Convert to seconds on the session time axis. Returns raw unchanged. */
    public double seconds() {
        return switch (unit) {
            case SECONDS -> value;
            case MILLISECONDS -> value / 1000.0;
            case SAMPLES -> throw new IllegalStateException(
                    "samples need a sample-rate basis; use Waveform.toSeconds(...)");
            default -> throw new IllegalStateException("not a time unit: " + unit.symbol);
        };
    }

    public double microvolts() {
        return switch (unit) {
            case UV -> value;
            case MV -> value * 1000.0;
            default -> throw new IllegalStateException("not an amplitude unit: " + unit.symbol);
        };
    }

    private static String format(double value, Unit unit) {
        return value + " " + unit.symbol;
    }

    @Override public String toString() { return raw; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantity q)) return false;
        return Double.compare(q.value, value) == 0 && q.unit == unit && Objects.equals(raw, q.raw);
    }
    @Override public int hashCode() { return Objects.hash(value, unit, raw); }
}
