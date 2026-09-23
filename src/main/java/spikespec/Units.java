package spikespec;

import java.util.Map;

/**
 * Unit registry. Import keeps the raw representation; only the derived layer
 * normalizes into base units. Unconvertible values stay in place and carry a
 * diagnostic instead of being silently dropped.
 */
public final class Units {
    public enum Kind { TIME, AMPLITUDE, LENGTH }

    public record Conversion(boolean ok, double value, String baseUnit, String note) {
        static Conversion ok(double v, String base) { return new Conversion(true, v, base, null); }
        static Conversion fail(String base, String note) { return new Conversion(false, Double.NaN, base, note); }
    }

    private static final Map<String, Double> TIME_TO_MS = Map.of(
            "ms", 1.0, "s", 1000.0, "us", 0.001);
    private static final Map<String, Double> AMP_TO_UV = Map.of(
            "uV", 1.0, "µV", 1.0, "mV", 1000.0);
    private static final Map<String, Double> LEN_TO_UM = Map.of(
            "um", 1.0, "µm", 1.0, "mm", 1000.0);

    private Units() {}

    public static String baseUnit(Kind kind) {
        return switch (kind) {
            case TIME -> "ms";
            case AMPLITUDE -> "uV";
            case LENGTH -> "um";
        };
    }

    public static Conversion convert(double value, String unit, Kind kind) {
        Map<String, Double> table = switch (kind) {
            case TIME -> TIME_TO_MS;
            case AMPLITUDE -> AMP_TO_UV;
            case LENGTH -> LEN_TO_UM;
        };
        String base = baseUnit(kind);
        if (unit == null) {
            return Conversion.fail(base, "missing unit; expected one of " + table.keySet());
        }
        Double factor = table.get(unit.trim());
        if (factor == null) {
            return Conversion.fail(base,
                    "unit '" + unit + "' not convertible to " + base + "; value kept in raw form");
        }
        return Conversion.ok(value * factor, base);
    }
}
