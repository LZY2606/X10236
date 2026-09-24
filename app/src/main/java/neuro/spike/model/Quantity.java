package neuro.spike.model;

import java.util.List;
import java.util.Set;
import neuro.spike.util.Diagnostic;

/**
 * An import-preserved physical quantity. The raw representation is never discarded:
 * {@code raw} is the exact imported token, {@code unit} the declared unit and {@code value}
 * the SI-normalised double used by the derived layer. When conversion is impossible the
 * value stays at the original position ({@code value} == null) and carries a diagnostic.
 */
public record Quantity(String raw, String unit, Double value, List<Diagnostic> diagnostics) {

    private static final Set<String> AMPLITUDE_UNITS = Set.of("uv", "µv", "μv", "mv", "v");
    private static final Set<String> LENGTH_UNITS = Set.of("um", "µm", "μm", "mm", "m");

    public boolean convertible() {
        return value != null;
    }

    public static Quantity amplitude(String raw, String unit, String pointer) {
        String normalisedUnit = unit == null ? "" : unit.trim().toLowerCase();
        Double v = parse(raw);
        if (v == null) {
            return new Quantity(raw, unit, null, List.of(new Diagnostic(
                    "Q-NUM", pointer, "amplitude is not numeric", raw)));
        }
        return switch (normalisedUnit) {
            case "uv", "µv", "μv" -> new Quantity(raw, unit, v, List.of());
            case "mv" -> new Quantity(raw, unit, v * 1000.0, List.of());
            case "v" -> new Quantity(raw, unit, v * 1_000_000.0, List.of());
            default -> new Quantity(raw, unit, null, List.of(new Diagnostic(
                    "Q-UNIT", pointer, "unknown amplitude unit: " + unit, raw)));
        };
    }

    public static Quantity length(String raw, String unit, String pointer) {
        String normalisedUnit = unit == null ? "" : unit.trim().toLowerCase();
        Double v = parse(raw);
        if (v == null) {
            return new Quantity(raw, unit, null, List.of(new Diagnostic(
                    "Q-NUM", pointer, "length is not numeric", raw)));
        }
        return switch (normalisedUnit) {
            case "um", "µm", "μm" -> new Quantity(raw, unit, v, List.of());
            case "mm" -> new Quantity(raw, unit, v * 1000.0, List.of());
            case "m" -> new Quantity(raw, unit, v * 1_000_000.0, List.of());
            default -> new Quantity(raw, unit, null, List.of(new Diagnostic(
                    "Q-UNIT", pointer, "unknown length unit: " + unit, raw)));
        };
    }

    private static Double parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
