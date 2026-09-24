package neurospike.model;

import java.util.Objects;

/**
 * A measured quantity that retains its original textual representation.
 *
 * @param raw      source text exactly as imported; never normalized away
 * @param value    parsed numeric value, or {@code null} when conversion failed
 * @param unit     physical unit (e.g. "uV", "um", "ms"); may be {@code null} for counts
 * @param frame    coordinate/time reference frame (e.g. "probeA", "sessionClock@0")
 * @param convert  true when {@code raw} was convertible into {@code value}
 * @param reason   conversion diagnosis when {@code convert} is false
 */
public record PhysicalValue(String raw, Double value, String unit, String frame,
                            boolean convert, String reason) {

    public PhysicalValue {
        Objects.requireNonNull(raw, "raw representation is mandatory");
    }

    public static PhysicalValue of(double value, String unit, String frame) {
        return new PhysicalValue(Double.toString(value), value, unit, frame, true, null);
    }

    public static PhysicalValue raw(String raw, String unit, String frame, String reason) {
        return new PhysicalValue(raw, null, unit, frame, false, reason);
    }

    /** Failures stay in place with a diagnosis rather than becoming zero/default. */
    public boolean valid() {
        return convert && value != null;
    }
}
