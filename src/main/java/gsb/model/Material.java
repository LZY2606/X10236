package gsb.model;

/** Immutable source material. Raw waveform references, probe geometry,
 *  detection thresholds, feature versions and cluster labels are stored
 *  separately from adjudication events. Id is content-addressed. */
public final class Material {
    public enum Kind { WAVEFORM_REFS, PROBE_GEOMETRY, DETECTION_THRESHOLD, FEATURE_VERSION, CLUSTER_LABELS }

    public final String id;        // mat-<content hash>
    public final Kind kind;
    public final String version;   // e.g. "thr-v2"
    public final String content;   // JSON, raw representation preserved
    public final String hash;
    public final long seq;         // import order

    public Material(String id, Kind kind, String version, String content, String hash, long seq) {
        this.id = id; this.kind = kind; this.version = version;
        this.content = content; this.hash = hash; this.seq = seq;
    }
}
