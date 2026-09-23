package gsb.model;

import java.util.List;

/** A putative neural unit. Identity is the uid; cluster labels are per
 *  label-set-version annotations and may be renumbered freely. */
public final class Unit {
    public final String uid;
    public final String label;        // current cluster label (display only)
    public final String labelSet;     // label set version the label came from
    public final List<String> eventIds;
    public final List<Evidence> evidence;
    public final String status;       // ACTIVE | MERGED | SPLIT_PARENT | CANDIDATE

    public Unit(String uid, String label, String labelSet, List<String> eventIds,
                List<Evidence> evidence, String status) {
        this.uid = uid; this.label = label; this.labelSet = labelSet;
        this.eventIds = eventIds; this.evidence = evidence; this.status = status;
    }
}
