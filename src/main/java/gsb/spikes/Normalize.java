package gsb.spikes;

import java.util.HashMap;
import java.util.Map;

/**
 * Derived-layer unit normalization. Import keeps the raw representation;
 * only here do we convert to canonical bases: ms, µV, µm.
 * Unconvertible values stay in place with a located diagnostic.
 */
public final class Normalize {
    private Normalize() {}

    private static final Map<String, Double> TIME_TO_MS = new HashMap<>();
    private static final Map<String, Double> VOLT_TO_UV = new HashMap<>();
    private static final Map<String, Double> DIST_TO_UM = new HashMap<>();

    static {
        TIME_TO_MS.put("ms", 1.0);
        TIME_TO_MS.put("s", 1000.0);
        TIME_TO_MS.put("µs", 0.001);
        TIME_TO_MS.put("us", 0.001);
        VOLT_TO_UV.put("µV", 1.0);
        VOLT_TO_UV.put("uV", 1.0);
        VOLT_TO_UV.put("mV", 1000.0);
        VOLT_TO_UV.put("V", 1_000_000.0);
        DIST_TO_UM.put("µm", 1.0);
        DIST_TO_UM.put("um", 1.0);
        DIST_TO_UM.put("mm", 1000.0);
    }

    public enum Dimension { TIME, VOLTAGE, DISTANCE }

    public static Types.Quantity quantity(double rawValue, String unit, String position, Dimension dim) {
        Map<String, Double> table = switch (dim) {
            case TIME -> TIME_TO_MS;
            case VOLTAGE -> VOLT_TO_UV;
            case DISTANCE -> DIST_TO_UM;
        };
        String base = switch (dim) {
            case TIME -> "ms";
            case VOLTAGE -> "µV";
            case DISTANCE -> "µm";
        };
        Double factor = unit == null ? null : table.get(unit.trim());
        if (factor == null) {
            return Types.Quantity.raw(String.valueOf(rawValue), unit,
                    new Types.Diagnostic(position, "unconvertible unit for " + dim, String.valueOf(rawValue), unit));
        }
        return Types.Quantity.of(rawValue * factor, base, rawValue + " " + unit);
    }

    /** Parse a raw textual quantity like "1.25 ms" or "-60 µV". */
    public static Types.Quantity parse(String raw, String position, Dimension dim) {
        if (raw == null || raw.isBlank()) {
            return Types.Quantity.raw(raw, null,
                    new Types.Diagnostic(position, "missing quantity", raw, null));
        }
        String[] parts = raw.trim().split("\\s+", 2);
        double value;
        try {
            value = Double.parseDouble(parts[0]);
        } catch (NumberFormatException e) {
            return Types.Quantity.raw(raw, null,
                    new Types.Diagnostic(position, "unparseable numeric value", raw, null));
        }
        String unit = parts.length > 1 ? parts[1].trim() : null;
        if (unit == null) {
            return Types.Quantity.raw(raw, null,
                    new Types.Diagnostic(position, "missing unit; value left unnormalized", raw, null));
        }
        return quantity(value, unit, position, dim);
    }
}
