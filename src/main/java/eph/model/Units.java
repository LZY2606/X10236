package eph.model;

import java.util.Map;

/**
 * Unit / coordinate-frame registry. Base units used in the derived layer:
 * time = nanoseconds, voltage = microvolts, length = micrometres, frequency = Hz.
 */
public final class Units {

    public static final Map<String, Double> TIME = Map.of(
            "ns", 1.0,
            "us", 1_000.0,
            "\u03bcs", 1_000.0,
            "ms", 1_000_000.0,
            "s", 1_000_000_000.0);

    public static final Map<String, Double> VOLTAGE = Map.of(
            "uV", 1.0,
            "\u00b5V", 1.0,
            "mV", 1_000.0,
            "V", 1_000_000.0);

    public static final Map<String, Double> LENGTH = Map.of(
            "um", 1.0,
            "\u00b5m", 1.0,
            "mm", 1_000.0,
            "m", 1_000_000.0);

    public static final Map<String, Double> FREQUENCY = Map.of(
            "Hz", 1.0,
            "kHz", 1_000.0);

    private Units() {
    }

    /** Returns value expressed in the family base unit, or null when the unit is unknown. */
    public static Double toBase(double value, String unit) {
        Double f = factor(unit);
        return f == null ? null : value * f;
    }

    public static Double factor(String unit) {
        if (unit == null) return null;
        Double f = TIME.get(unit);
        if (f != null) return f;
        f = VOLTAGE.get(unit);
        if (f != null) return f;
        f = LENGTH.get(unit);
        if (f != null) return f;
        return FREQUENCY.get(unit);
    }

    public static String family(String unit) {
        if (TIME.containsKey(unit)) return "time";
        if (VOLTAGE.containsKey(unit)) return "voltage";
        if (LENGTH.containsKey(unit)) return "length";
        if (FREQUENCY.containsKey(unit)) return "frequency";
        return "unknown";
    }
}
