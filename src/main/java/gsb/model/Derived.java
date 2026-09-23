package gsb.model;

import java.util.List;

/** A computed record. Always carries input digest, parameter version and
 *  parent dependencies, plus validity interval and invalidation reason. */
public final class Derived {
    public enum Kind { FEATURE_PROJECTION, AUTOCORRELOGRAM, FIRING_RATE, DRIFT_TIMELINE }
    public enum Status { VALID, INVALIDATED, SUPERSEDED, CANDIDATE }

    public final String id;
    public final Kind kind;
    public final String unitUid;
    public final String inputDigest;
    public final String paramVersion;
    public final List<String> parents;   // material / decision ids
    public final String payload;         // JSON
    public final Status status;
    public final double validFromS;
    public final double validToS;        // half-open [from, to)
    public final String invalidation;    // reason, null when VALID
    public final long seq;

    public Derived(String id, Kind kind, String unitUid, String inputDigest, String paramVersion,
                   List<String> parents, String payload, Status status,
                   double validFromS, double validToS, String invalidation, long seq) {
        this.id = id; this.kind = kind; this.unitUid = unitUid; this.inputDigest = inputDigest;
        this.paramVersion = paramVersion; this.parents = parents; this.payload = payload;
        this.status = status; this.validFromS = validFromS; this.validToS = validToS;
        this.invalidation = invalidation; this.seq = seq;
    }
}
