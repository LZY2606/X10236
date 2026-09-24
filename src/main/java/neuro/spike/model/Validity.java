package neuro.spike.model;

/** Lifecycle of a derived result relative to its inputs. */
public enum Validity {
    VALID,
    /** Recomputed/superseded by a newer revision of its dependency (kept for history). */
    SUPERSEDED,
    /** Cannot be computed in this region because an input is locally broken. */
    LOCALLY_INVALID
}
