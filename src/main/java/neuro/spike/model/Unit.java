package neuro.spike.model;

/**
 * Physical unit or coordinate/time reference carried by every domain quantity.
 * Quantities never travel without a reference; conversion happens only in the
 * derived layer and unconvertible values stay in place with a diagnostic.
 */
public enum Unit {
    UV("uV", "microvolts"),
    MV("mV", "millivolts"),
    SAMPLES("samples", "sample index"),
    SECONDS("s", "seconds relative to time origin"),
    MILLISECONDS("ms", "milliseconds relative to time origin"),
    HZ("Hz", "hertz"),
    UM("um", "micrometres"),
    OHM("ohm", "ohms"),
    NONE("none", "dimensionless");

    public final String symbol;
    public final String description;

    Unit(String symbol, String description) {
        this.symbol = symbol;
        this.description = description;
    }

    public static Unit fromSymbol(String s) {
        if (s == null) throw new IllegalArgumentException("missing unit symbol");
        for (Unit u : values()) if (u.symbol.equalsIgnoreCase(s.trim())) return u;
        throw new IllegalArgumentException("unknown unit symbol: " + s);
    }
}
