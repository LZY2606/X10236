package ephys;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** A domain quantity. The raw representation is always preserved; the derived
 *  layer normalizes to a canonical unit per dimension. Values that cannot be
 *  converted stay in place (value == null) with a diagnostic attached. */
public record Quantity(String raw, Double value, String unit, String dimension) {

    static final Map<String, String[]> UNITS = Map.ofEntries(
        Map.entry("ms", new String[] {"time", "1"}),
        Map.entry("s", new String[] {"time", "1000"}),
        Map.entry("us", new String[] {"time", "0.001"}),
        Map.entry("µs", new String[] {"time", "0.001"}),
        Map.entry("uV", new String[] {"voltage", "1"}),
        Map.entry("µV", new String[] {"voltage", "1"}),
        Map.entry("mV", new String[] {"voltage", "1000"}),
        Map.entry("V", new String[] {"voltage", "1000000"}),
        Map.entry("um", new String[] {"length", "1"}),
        Map.entry("µm", new String[] {"length", "1"}),
        Map.entry("mm", new String[] {"length", "1000"}),
        Map.entry("Hz", new String[] {"rate", "1"}),
        Map.entry("kHz", new String[] {"rate", "1000"}));

    public static String canonicalUnit(String dimension) {
        return switch (dimension) {
            case "time" -> "ms";
            case "voltage" -> "uV";
            case "length" -> "um";
            case "rate" -> "Hz";
            default -> throw new IllegalArgumentException("unknown dimension " + dimension);
        };
    }

    /** Parse a quantity node: {"value": v, "unit": "uV"} or a bare number
     *  (assumed already canonical for the expected dimension). */
    public static Quantity from(JsonNode node, String expectedDim, List<Diag> diags, String path) {
        if (node == null || node.isNull()) {
            diags.add(Diag.error("quantity", path, "missing quantity, expected dimension " + expectedDim));
            return new Quantity("null", null, null, expectedDim);
        }
        if (node.isNumber()) {
            return new Quantity(node.asText(), node.doubleValue(), canonicalUnit(expectedDim), expectedDim);
        }
        if (node.isObject() && node.has("value") && node.get("value").isNumber()) {
            double v = node.get("value").doubleValue();
            String unit = node.hasNonNull("unit") ? node.get("unit").asText() : canonicalUnit(expectedDim);
            String raw = Json.write(node);
            String[] meta = UNITS.get(unit);
            if (meta == null) {
                diags.add(Diag.warning("quantity", path,
                    "unknown unit '" + unit + "'; value kept in raw position, not normalized"));
                return new Quantity(raw, null, unit, expectedDim);
            }
            if (!meta[0].equals(expectedDim)) {
                diags.add(Diag.warning("quantity", path,
                    "unit '" + unit + "' is " + meta[0] + " but " + expectedDim
                        + " was expected; value kept in raw position"));
                return new Quantity(raw, null, unit, expectedDim);
            }
            double factor = Double.parseDouble(meta[1]);
            return new Quantity(raw, v * factor, unit, expectedDim);
        }
        diags.add(Diag.error("quantity", path, "unparseable quantity: " + Json.write(node)));
        return new Quantity(Json.write(node), null, null, expectedDim);
    }
}
