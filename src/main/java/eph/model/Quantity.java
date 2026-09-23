package eph.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A domain quantity that preserves its raw representation on import.
 * Normalization to a base unit happens only in the derived layer; values that
 * cannot be converted stay in place with a diagnostic instead of being dropped.
 */
public final class Quantity {
    public final String raw;
    public final String unit;
    public final String frame;
    public final Double value;

    private Quantity(String raw, Double value, String unit, String frame) {
        this.raw = raw;
        this.value = value;
        this.unit = unit;
        this.frame = frame;
    }

    public static Quantity parsed(double value, String unit) {
        return new Quantity(format(value, unit), value, unit, null);
    }

    public static Quantity rawOnly(String raw, String unit) {
        return new Quantity(raw, null, unit, null);
    }

    public static Quantity fromMap(Map<String, Object> m) {
        String raw = m.get("raw") == null ? null : String.valueOf(m.get("raw"));
        String unit = m.get("unit") == null ? null : String.valueOf(m.get("unit"));
        String frame = m.get("frame") == null ? null : String.valueOf(m.get("frame"));
        Double value = null;
        if (m.get("value") instanceof Number n) {
            value = n.doubleValue();
        }
        Quantity q = new Quantity(raw, value, unit, frame);
        return q;
    }

    public Quantity withFrame(String newFrame) {
        return new Quantity(raw, value, unit, newFrame);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("raw", raw);
        if (value != null) m.put("value", value);
        if (unit != null) m.put("unit", unit);
        if (frame != null) m.put("frame", frame);
        return m;
    }

    private static String format(double value, String unit) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return (long) value + (unit == null ? "" : " " + unit);
        }
        return value + (unit == null ? "" : " " + unit);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantity q)) return false;
        return java.util.Objects.equals(raw, q.raw)
                && java.util.Objects.equals(unit, q.unit)
                && java.util.Objects.equals(frame, q.frame);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(raw, unit, frame);
    }
}
