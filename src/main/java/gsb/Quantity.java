package gsb;

import java.util.Map;

/**
 * A domain quantity keeps its raw representation (as imported) plus an optional
 * normalized value in a canonical unit. Values that cannot be converted stay raw
 * and get a diagnostic; they are never silently dropped or replaced by defaults.
 */
public final class Quantity {
    /** Canonical units per dimension. */
    public static final Map<String, String> CANONICAL = Map.of(
            "time", "s",
            "amplitude", "uV",
            "rate", "Hz",
            "length", "um");

    /** unit -> (dimension, multiplier to canonical). */
    private static final Map<String, String[]> UNITS = Map.ofEntries(
            Map.entry("s", new String[]{"time", "1"}),
            Map.entry("ms", new String[]{"time", "0.001"}),
            Map.entry("us", new String[]{"time", "0.000001"}),
            Map.entry("min", new String[]{"time", "60"}),
            Map.entry("uV", new String[]{"amplitude", "1"}),
            Map.entry("mV", new String[]{"amplitude", "1000"}),
            Map.entry("V", new String[]{"amplitude", "1000000"}),
            Map.entry("Hz", new String[]{"rate", "1"}),
            Map.entry("kHz", new String[]{"rate", "1000"}),
            Map.entry("um", new String[]{"length", "1"}),
            Map.entry("mm", new String[]{"length", "1000"}));

    public final String rawValue;
    public final String rawUnit;
    public final String basis; // coordinate / time reference frame, may be null
    private final Double normalized; // canonical-unit value, null when unconvertible
    private final String problem;    // why normalization failed, null when ok

    private Quantity(String rawValue, String rawUnit, String basis, Double normalized, String problem) {
        this.rawValue = rawValue;
        this.rawUnit = rawUnit;
        this.basis = basis;
        this.normalized = normalized;
        this.problem = problem;
    }

    public static Quantity of(String rawValue, String rawUnit, String basis) {
        Double value = null;
        String problem = null;
        if (rawValue == null || rawValue.isBlank()) {
            problem = "missing value";
        } else {
            try {
                double v = Double.parseDouble(rawValue.trim());
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    problem = "non-finite value: " + rawValue;
                } else if (rawUnit == null || rawUnit.isBlank()) {
                    problem = "missing unit";
                } else {
                    String[] info = UNITS.get(rawUnit.trim());
                    if (info == null) {
                        problem = "unknown unit: " + rawUnit;
                    } else {
                        value = v * Double.parseDouble(info[1]);
                    }
                }
            } catch (NumberFormatException e) {
                problem = "unparseable number: " + rawValue;
            }
        }
        return new Quantity(rawValue, rawUnit, basis, value, problem);
    }

    public boolean convertible() { return normalized != null; }
    public Double normalized() { return normalized; }
    public String problem() { return problem; }

    public String dimension() {
        if (rawUnit == null) return null;
        String[] info = UNITS.get(rawUnit.trim());
        return info == null ? null : info[0];
    }
}
