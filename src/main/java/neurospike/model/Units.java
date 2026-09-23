package neurospike.model;

import java.util.Map;

/**
 * Unit normalization. Raw representation is always preserved by the caller;
 * this only computes the canonical (derived) value. Unknown units return null
 * so the caller can leave the value in place with a diagnostic.
 */
public final class Units {
    private Units() {}

    public record Conversion(double value, String canonicalUnit) {}

    private static final Map<String, double[]> VOLTAGE = Map.of(
            "uV", new double[]{1.0, 0}, "µV", new double[]{1.0, 0},
            "mV", new double[]{1000.0, 0}, "V", new double[]{1_000_000.0, 0});
    private static final Map<String, double[]> TIME = Map.of(
            "ms", new double[]{1.0, 0}, "s", new double[]{1000.0, 0},
            "us", new double[]{0.001, 0}, "µs", new double[]{0.001, 0});
    private static final Map<String, double[]> FREQ = Map.of(
            "Hz", new double[]{1.0, 0}, "kHz", new double[]{1000.0, 0});
    private static final Map<String, double[]> LENGTH = Map.of(
            "um", new double[]{1.0, 0}, "µm", new double[]{1.0, 0},
            "mm", new double[]{1000.0, 0});

    /** dimension: voltage|time|freq|length. Returns null when unit unknown. */
    public static Conversion convert(String rawValue, String unit, String dimension) {
        double v;
        try {
            v = Double.parseDouble(rawValue.trim());
        } catch (Exception e) {
            return null;
        }
        Map<String, double[]> table = switch (dimension) {
            case "voltage" -> VOLTAGE;
            case "time" -> TIME;
            case "freq" -> FREQ;
            case "length" -> LENGTH;
            default -> null;
        };
        if (table == null) return null;
        double[] f = table.get(unit);
        if (f == null) return null;
        String canonical = switch (dimension) {
            case "voltage" -> "µV";
            case "time" -> "ms";
            case "freq" -> "Hz";
            default -> "µm";
        };
        return new Conversion(v * f[0], canonical);
    }
}
