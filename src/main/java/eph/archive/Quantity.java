package eph.archive;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A physical quantity that preserves its original textual representation.
 *
 * <p>Import keeps {@link #raw} verbatim; the derived layer is the only place that normalises to
 * {@link #canonicalValue} with the canonical {@link #canonicalUnit}. Values that cannot be
 * converted remain at the original position with a {@link Diagnostic}; they are never dropped
 * to satisfy a plotting pipeline.
 */
public record Quantity(String raw, Double value, String unit, Double canonicalValue, String canonicalUnit) {

    private static final Pattern NUMBER = Pattern.compile("\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*(\\S*)\\s*");

    /** Known multiplicative conversions to canonical units. */
    private static final java.util.Map<String, UnitFactor> UNITS = java.util.Map.ofEntries(
            java.util.Map.entry("ms", new UnitFactor("s", 0.001)),
            java.util.Map.entry("s", new UnitFactor("s", 1.0)),
            java.util.Map.entry("us", new UnitFactor("s", 0.000001)),
            java.util.Map.entry("µs", new UnitFactor("s", 0.000001)),
            java.util.Map.entry("µV", new UnitFactor("V", 0.000001)),
            java.util.Map.entry("uV", new UnitFactor("V", 0.000001)),
            java.util.Map.entry("mV", new UnitFactor("V", 0.001)),
            java.util.Map.entry("V", new UnitFactor("V", 1.0)),
            java.util.Map.entry("um", new UnitFactor("m", 0.000001)),
            java.util.Map.entry("µm", new UnitFactor("m", 0.000001)),
            java.util.Map.entry("mm", new UnitFactor("m", 0.001)),
            java.util.Map.entry("m", new UnitFactor("m", 1.0)),
            java.util.Map.entry("Hz", new UnitFactor("Hz", 1.0)),
            java.util.Map.entry("kHz", new UnitFactor("Hz", 1000.0)));

    public record UnitFactor(String canonical, double factor) {
    }

    public static Quantity parse(String raw, String location, List<Diagnostic> diagnostics) {
        if (raw == null || raw.isBlank()) {
            diagnostics.add(Diagnostic.error("MISSING_QUANTITY", location, "quantity is absent"));
            return new Quantity(raw, null, null, null, null);
        }
        Matcher m = NUMBER.matcher(raw);
        if (!m.matches()) {
            diagnostics.add(Diagnostic.local("UNPARSEABLE_QUANTITY", location,
                    "value '" + raw + "' is not a numeric quantity; retained as raw"));
            return new Quantity(raw, null, null, null, null);
        }
        double v;
        try {
            v = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.local("UNPARSEABLE_NUMBER", location,
                    "numeric part of '" + raw + "' could not be parsed; retained as raw"));
            return new Quantity(raw, null, null, null, null);
        }
        String u = m.group(2);
        if (u.isEmpty()) {
            diagnostics.add(Diagnostic.local("MISSING_UNIT", location,
                    "'" + raw + "' has no unit; retained as raw, not normalised"));
            return new Quantity(raw, v, null, null, null);
        }
        UnitFactor f = UNITS.get(u);
        if (f == null) {
            diagnostics.add(Diagnostic.local("UNKNOWN_UNIT", location,
                    "unit '" + u + "' in '" + raw + "' cannot be converted; retained as raw"));
            return new Quantity(raw, v, u, null, null);
        }
        return new Quantity(raw, v, u, v * f.factor(), f.canonical());
    }

    public boolean usable() {
        return canonicalValue != null;
    }
}
