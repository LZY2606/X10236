package org.neuro.spike.util;

import org.neuro.spike.model.Quantity;

/**
 * Parsing of domain measurements. Raw representation is always preserved;
 * canonical SI conversion happens here for the derivation layer. Anything
 * unconvertible is returned with a diagnostic rather than coerced.
 */
public final class Quantities {
    private Quantities() {
    }

    /** Time: seconds baseline. Accepts e.g. "0.0120 s", "12.0 ms", "12000 us". */
    public static Quantity time(String raw) {
        String[] parts = split(raw);
        if (parts == null) {
            return Quantity.unconvertible(raw, "time: expected '<number> s|ms|us'");
        }
        double v;
        try {
            v = Double.parseDouble(parts[0]);
        } catch (NumberFormatException e) {
            return Quantity.unconvertible(raw, "time: non-numeric magnitude");
        }
        double factor = switch (parts[1) {
            case "s" -> 1.0;
            case "ms" -> 1e-3;
            case "us" -> 1e-6;
            default -> Double.NaN;
        };
        if (Double.isNaN(factor)) {
            return Quantity.unconvertible(raw, "time: unknown unit '" + parts[1] + "'");
        }
        return Quantity.parsed(raw, v * factor, "s");
    }

    /** Amplitude/voltage: volts baseline. Accepts uV, mV, V; a sentinel like "OVL" stays in place. */
    public static Quantity amplitude(String raw) {
        String[] parts = split(raw);
        if (parts == null) {
            return Quantity.unconvertible(raw, "amplitude: expected '<number> uV|mV|V'");
        }
        double v;
        try {
            v = Double.parseDouble(parts[0]);
        } catch (NumberFormatException e) {
            return Quantity.unconvertible(raw, "amplitude: non-numeric magnitude");
        }
        double factor = switch (parts[1]) {
            case "V" -> 1.0;
            case "mV" -> 1e-3;
            case "uV" -> 1e-6;
            default -> Double.NaN;
        };
        if (Double.isNaN(factor)) {
            return Quantity.unconvertible(raw, "amplitude: unknown unit '" + parts[1] + "'");
        }
        return Quantity.parsed(raw, v * factor, "V");
    }

    /** Probe coordinate in micrometres relative to the probe geometry origin. */
    public static Quantity coordinate(String raw) {
        String[] parts = split(raw);
        if (parts == null) {
            return Quantity.unconvertible(raw, "coordinate: expected '<number> um'");
        }
        double v;
        try {
            v = Double.parseDouble(parts[0]);
        } catch (NumberFormatException e) {
            return Quantity.unconvertible(raw, "coordinate: non-numeric magnitude");
        }
        if (!"um".equals(parts[1])) {
            return Quantity.unconvertible(raw, "coordinate: only 'um' supported relative to probe origin");
        }
        return Quantity.parsed(raw, v * 1e-6, "m");
    }

    private static String[] split(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int i = trimmed.length();
        while (i > 0 && !Character.isDigit(trimmed.charAt(i - 1)) && trimmed.charAt(i - 1) != '.') {
            i--;
        }
        if (i <= 0 || i >= trimmed.length()) {
            return null;
        }
        return new String[]{trimmed.substring(0, i).trim(), trimmed.substring(i).trim()};
    }
}
