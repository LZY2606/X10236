package gsb.core;

import java.util.Map;

/** Domain quantities always carry a unit (and, for geometry, a coordinate basis).
 *  Raw representations are preserved at import; only the derived layer normalizes. */
public final class Quantity {
    private Quantity() {}

    private static final Map<String, Double> TO_UV = Map.of(
            "uV", 1.0,
            "mV", 1000.0);
    private static final Map<String, Double> TO_MS = Map.of(
            "ms", 1.0,
            "s", 1000.0,
            "us", 0.001);

    /** @return normalized value in uV, or null when the unit is unknown (caller
     *          must keep the raw value and emit a diagnostic). */
    public static Double toMicrovolts(double value, String unit) {
        Double f = TO_UV.get(unit);
        return f == null ? null : value * f;
    }

    /** @return normalized value in ms, or null when the unit is unknown. */
    public static Double toMillis(double value, String unit) {
        Double f = TO_MS.get(unit);
        return f == null ? null : value * f;
    }

    public static boolean knownAmplitudeUnit(String unit) { return TO_UV.containsKey(unit); }
    public static boolean knownTimeUnit(String unit) { return TO_MS.containsKey(unit); }
}
