package spikesort.units;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domain quantities are stored raw (text + unit + coordinate basis) at import time.
 * Normalization to canonical units happens only in the derived layer, via this class.
 * Unconvertible values yield null and must be reported as positioned diagnostics;
 * they are never silently replaced by defaults.
 */
public final class Units {
    private Units() {}

    public record Parsed(Double number, String unit) {}

    private static final Pattern NUMBER =
            Pattern.compile("^\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*(.*)$");

    /** Splits a raw quantity like "1.25 ms" into number and unit. Null number = unparseable. */
    public static Parsed parse(String raw) {
        if (raw == null) return new Parsed(null, "");
        Matcher m = NUMBER.matcher(raw.trim());
        if (!m.matches()) return new Parsed(null, raw.trim());
        String unit = m.group(2).trim();
        try {
            return new Parsed(Double.parseDouble(m.group(1)), unit);
        } catch (NumberFormatException e) {
            return new Parsed(null, unit);
        }
    }

    /** dimension -> canonical unit */
    public static final Map<String, String> CANONICAL = Map.of(
            "time", "ms",
            "voltage", "uV",
            "length", "um",
            "rate", "Hz",
            "ratio", "x");

    private static final Map<String, double[]> FACTORS = Map.ofEntries(
            // dimension encoded via factor table: {factorToCanonical, dimCode}
            Map.entry("ms", new double[]{1.0, 1}), Map.entry("s", new double[]{1000.0, 1}),
            Map.entry("us", new double[]{0.001, 1}), Map.entry("µs", new double[]{0.001, 1}),
            Map.entry("min", new double[]{60000.0, 1}),
            Map.entry("uV", new double[]{1.0, 2}), Map.entry("µV", new double[]{1.0, 2}),
            Map.entry("mV", new double[]{1000.0, 2}), Map.entry("V", new double[]{1_000_000.0, 2}),
            Map.entry("um", new double[]{1.0, 3}), Map.entry("µm", new double[]{1.0, 3}),
            Map.entry("mm", new double[]{1000.0, 3}),
            Map.entry("Hz", new double[]{1.0, 4}), Map.entry("kHz", new double[]{1000.0, 4}),
            Map.entry("x", new double[]{1.0, 5}), Map.entry("σ", new double[]{1.0, 5}),
            Map.entry("sigma", new double[]{1.0, 5}));

    private static final Map<String, Double> DIM_CODE = Map.of(
            "time", 1.0, "voltage", 2.0, "length", 3.0, "rate", 4.0, "ratio", 5.0);

    /**
     * Converts a parsed quantity to the canonical unit of the requested dimension.
     * Returns null when the unit is unknown or belongs to another dimension.
     */
    public static Double toCanonical(Parsed parsed, String dimension) {
        if (parsed.number() == null) return null;
        double[] entry = FACTORS.get(parsed.unit());
        if (entry == null) return null;
        if (entry[1] != DIM_CODE.get(dimension)) return null;
        return parsed.number() * entry[0];
    }

    public static Double toCanonical(String raw, String dimension) {
        return toCanonical(parse(raw), dimension);
    }
}
