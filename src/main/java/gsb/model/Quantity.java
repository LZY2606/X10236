package gsb.model;

/** A domain quantity. Raw representation is always preserved; normalization
 *  happens only in the derived layer. Unconvertible values keep their raw
 *  form and carry a diagnostic instead of being silently dropped. */
public final class Quantity {
    public final String raw;
    public final String unit;    // e.g. "uV", "ms", "s", "Hz", "a.u."
    public final String basis;   // coordinate / time basis, e.g. "probe-um", "recording-clock"
    public final Double normalized; // null when not convertible
    public final String diagnostic; // non-null when normalization failed

    private Quantity(String raw, String unit, String basis, Double normalized, String diagnostic) {
        this.raw = raw; this.unit = unit; this.basis = basis;
        this.normalized = normalized; this.diagnostic = diagnostic;
    }

    public static Quantity of(String raw, String unit, String basis) {
        if (raw == null) {
            return new Quantity(null, unit, basis, null, "missing value for unit " + unit);
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return new Quantity(raw, unit, basis, v, null);
        } catch (NumberFormatException e) {
            return new Quantity(raw, unit, basis, null,
                "unconvertible value '" + raw + "' for unit " + unit);
        }
    }

    public boolean convertible() { return normalized != null; }
}
