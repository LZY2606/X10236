package gsb.model;

/** An adjudication event. Append-only; adopted and non-adopted outcomes are
 *  both preserved. Conflicting human decisions coexist with status CONFLICT. */
public final class Decision {
    public enum Kind { ACCEPT, REJECT, MERGE, SPLIT, PARAM_ADJUST }
    public enum Status { ADOPTED, NOT_ADOPTED, CONFLICT }

    public final String id;
    public final long seq;
    public final Kind kind;
    public final String targets;   // JSON array of unit uids
    public final String params;    // JSON
    public final String rationale;
    public final String branch;
    public final Status status;
    public final String author;

    public Decision(String id, long seq, Kind kind, String targets, String params,
                    String rationale, String branch, Status status, String author) {
        this.id = id; this.seq = seq; this.kind = kind; this.targets = targets;
        this.params = params; this.rationale = rationale; this.branch = branch;
        this.status = status; this.author = author;
    }
}
