package gsb.model;

/** Machine-readable evidence. Refractory violations, amplitude drift and
 *  multi-channel propagation are EVIDENCE ONLY; none of them may be used as
 *  a stable identity for a unit. */
public final class Evidence {
    public enum Kind { REFRACTORY_VIOLATION, AMPLITUDE_DRIFT, MULTI_CHANNEL_PROPAGATION }
    public final Kind kind;
    public final String detail;     // machine-readable JSON fragment
    public final double atSeconds;  // when the evidence was observed (recording-clock)

    public Evidence(Kind kind, String detail, double atSeconds) {
        this.kind = kind; this.detail = detail; this.atSeconds = atSeconds;
    }
}
