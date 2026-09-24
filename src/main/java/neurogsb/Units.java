package neurogsb;

import java.util.Locale;

/**
 * Unit / coordinate-basis normalization. Raw representation is always kept;
 * normalization happens only in the derived layer. Unconvertible values stay
 * in place and produce a UNIT_UNCONVERTIBLE diagnostic.
 */
public final class Units {
    private Units() {}

    public static Double timeToMs(String raw, String unit) {
        Double v = parseNum(raw);
        if (v == null || unit == null) return null;
        return switch (norm(unit)) {
            case "ms" -> v;
            case "s" -> v * 1000.0;
            case "us", "µs" -> v / 1000.0;
            case "min" -> v * 60000.0;
            default -> null;
        };
    }

    public static Double amplitudeToUv(String raw, String unit) {
        Double v = parseNum(raw);
        if (v == null || unit == null) return null;
        return switch (norm(unit)) {
            case "uv", "µv" -> v;
            case "mv" -> v * 1000.0;
            case "v" -> v * 1_000_000.0;
            default -> null;
        };
    }

    public static Double lengthToUm(String raw, String unit) {
        Double v = parseNum(raw);
        if (v == null || unit == null) return null;
        return switch (norm(unit)) {
            case "um", "µm" -> v;
            case "mm" -> v * 1000.0;
            default -> null;
        };
    }

    public static boolean knownTimeUnit(String unit) {
        if (unit == null) return false;
        return switch (norm(unit)) { case "ms", "s", "us", "µs", "min" -> true; default -> false; };
    }

    public static boolean knownAmplitudeUnit(String unit) {
        if (unit == null) return false;
        return switch (norm(unit)) { case "uv", "µv", "mv", "v" -> true; default -> false; };
    }

    public static boolean knownLengthUnit(String unit) {
        if (unit == null) return false;
        return switch (norm(unit)) { case "um", "µm", "mm" -> true; default -> false; };
    }

    private static String norm(String unit) {
        return unit.trim().toLowerCase(Locale.ROOT);
    }

    private static Double parseNum(String raw) {
        if (raw == null) return null;
        try { return Double.parseDouble(raw.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
