package com.neurospike.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * A domain quantity carrying unit and coordinate/time basis. The raw
 * representation is preserved at import; normalization happens only in the
 * derived layer. Unconvertible values stay in place (value == null) with a
 * positioned diagnostic - they are never dropped for plotting convenience.
 */
public record Quantity(Double value, String unit, String basis, String raw) {

    private static final Map<String, Double> TIME_TO_MS = Map.of(
            "ms", 1.0, "s", 1000.0, "us", 0.001, "µs", 0.001);
    private static final Map<String, Double> AMP_TO_UV = Map.of(
            "uV", 1.0, "µV", 1.0, "mV", 1000.0, "V", 1_000_000.0);
    private static final Map<String, Double> DIST_TO_UM = Map.of(
            "um", 1.0, "µm", 1.0, "mm", 1000.0);

    public static Quantity ofRaw(double value, String unit, String basis) {
        return new Quantity(value, unit, basis, null);
    }

    /**
     * Parse {"value":..,"unit":..,"basis":..} and normalize to the canonical
     * unit of the expected dimension ("time"->ms, "amplitude"->uV,
     * "distance"->um). On failure the returned quantity has null value and a
     * diagnostic is appended with the exact position.
     */
    public static Quantity parse(JsonNode node, String dimension, List<Diagnostic> diags, String position) {
        if (node == null || !node.isObject()) {
            diags.add(Diagnostic.error("normalize", position, "quantity object missing"));
            return new Quantity(null, null, null, node == null ? null : node.toString());
        }
        JsonNode v = node.get("value");
        String unit = node.hasNonNull("unit") ? node.get("unit").asText() : null;
        String basis = node.hasNonNull("basis") ? node.get("basis").asText() : null;
        String raw = Json.canonical(node);
        if (v == null || !v.isNumber()) {
            diags.add(Diagnostic.error("normalize", position + ".value",
                    "quantity value is not numeric: " + (v == null ? "missing" : v.toString())));
            return new Quantity(null, unit, basis, raw);
        }
        double x = v.asDouble();
        Map<String, Double> table = switch (dimension) {
            case "time" -> TIME_TO_MS;
            case "amplitude" -> AMP_TO_UV;
            case "distance" -> DIST_TO_UM;
            default -> null;
        };
        if (table == null) {
            diags.add(Diagnostic.error("normalize", position, "unknown dimension: " + dimension));
            return new Quantity(null, unit, basis, raw);
        }
        Double factor = unit == null ? null : table.get(unit);
        if (factor == null) {
            diags.add(Diagnostic.error("normalize", position + ".unit",
                    "cannot convert unit '" + unit + "' to canonical " + dimension + " unit; value kept raw"));
            return new Quantity(null, unit, basis, raw);
        }
        return new Quantity(x * factor, canonicalUnit(dimension), basis, raw);
    }

    public static String canonicalUnit(String dimension) {
        return switch (dimension) {
            case "time" -> "ms";
            case "amplitude" -> "uV";
            case "distance" -> "um";
            default -> throw new IllegalArgumentException(dimension);
        };
    }
}
