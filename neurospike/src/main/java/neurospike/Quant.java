package neurospike;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/**
 * A measured quantity. The importer keeps the original textual representation;
 * normalization to a canonical unit happens only in the derived layer. Values
 * that cannot be converted stay in place with status=UNCONVERTIBLE so they are
 * never dropped just to make plots possible.
 */
@JsonDeserialize(using = Quant.Deserializer.class)
public record Quant(String originalText, Double value, String unit, String basis,
                    String status, String diagnostic) {

    public static Quant of(double value, String unit) {
        return new Quant(Double.toString(value), value, unit, null, "OK", null);
    }

    public static Quant of(double value, String unit, String basis) {
        return new Quant(Double.toString(value), value, unit, basis, "OK", null);
    }

    public static Quant unconvertible(String originalText, String diagnostic) {
        return new Quant(originalText, null, null, null, "UNCONVERTIBLE", diagnostic);
    }

    public boolean ok() {
        return "OK".equals(status);
    }

    /** Derived-layer normalization to one of the supported units; null when unconvertible. */
    public Double valueIn(String targetUnit) {
        if (!ok() || value == null || unit == null) {
            return null;
        }
        double factor = unitFactor(unit, targetUnit);
        if (Double.isNaN(factor)) {
            return null;
        }
        return value * factor;
    }

    static double unitFactor(String from, String to) {
        if (from.equals(to)) {
            return 1.0;
        }
        double fromBase = siPrefixFactor(from);
        double toBase = siPrefixFactor(to);
        if (Double.isNaN(fromBase) || Double.isNaN(toBase)) {
            return Double.NaN;
        }
        // only same physical family is accepted by callers via equal base symbol
        if (!baseSymbol(from).equals(baseSymbol(to))) {
            return Double.NaN;
        }
        return fromBase / toBase;
    }

    private static String baseSymbol(String u) {
        if (u.equals("V") || u.endsWith("V")) {
            return "V";
        }
        if (u.equals("m") || u.endsWith("m")) {
            return "m";
        }
        if (u.equals("s") || u.endsWith("s")) {
            return "s";
        }
        if (u.equals("Hz") || u.endsWith("Hz")) {
            return "Hz";
        }
        return u;
    }

    private static double siPrefixFactor(String u) {
        String b = baseSymbol(u);
        String prefix = u.substring(0, u.length() - b.length());
        return switch (prefix) {
            case "" -> 1.0;
            case "u", "\u00b5" -> 1e-6;
            case "m" -> 1e-3;
            case "k" -> 1e3;
            default -> Double.NaN;
        };
    }

    public static final class Deserializer extends JsonDeserializer<Quant> {
        @Override
        public Quant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();
            String original = node.toString();
            if (node.isNumber()) {
                return new Quant(original, node.asDouble(), null, null, "OK", null);
            }
            if (node.isTextual()) {
                String text = node.asText();
                String[] parts = text.trim().split("\\s+", 2);
                try {
                    double v = Double.parseDouble(parts[0].trim());
                    String u = parts.length > 1 ? parts[1].trim() : null;
                    return new Quant(text, v, u, null, "OK", null);
                } catch (NumberFormatException bad) {
                    return new Quant(text, null, null, null, "UNCONVERTIBLE",
                            "cannot parse quantity from text: " + text);
                }
            }
            if (node.isObject()) {
                String status = node.path("status").asText("OK");
                JsonNode vnode = node.get("value");
                Double v = vnode != null && vnode.isNumber() ? vnode.asDouble() : null;
                String unit = node.path("unit").asText(null);
                String basis = node.path("basis").asText(null);
                String diag = node.path("diagnostic").asText(null);
                String originalText = node.has("original") ? node.get("original").asText() : original;
                if ("UNCONVERTIBLE".equals(status) || v == null) {
                    return new Quant(originalText, v, unit, basis, "UNCONVERTIBLE",
                            diag != null ? diag : "value missing or unconvertible");
                }
                return new Quant(originalText, v, unit, basis, "OK", diag);
            }
            return new Quant(original, null, null, null, "UNCONVERTIBLE",
                    "quantity must be a number, text 'value unit' or an object");
        }
    }
}
