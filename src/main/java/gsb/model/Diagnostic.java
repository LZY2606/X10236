package gsb.model;

/** A positioned diagnostic. Incomplete input produces these; the engine never
 *  fakes success with nulls or defaults. */
public final class Diagnostic {
    public final String owner;    // id of the material / unit / derived record
    public final String position; // e.g. "segments[2].spikes[4].amp"
    public final String severity; // ERROR | WARN
    public final String code;
    public final String message;

    public Diagnostic(String owner, String position, String severity, String code, String message) {
        this.owner = owner; this.position = position; this.severity = severity;
        this.code = code; this.message = message;
    }
}
