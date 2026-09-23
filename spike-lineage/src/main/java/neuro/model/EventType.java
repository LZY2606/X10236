package neuro.model;

/**
 * Event taxonomy. MATERIAL_* events are immutable source facts; everything else is a reversible /
 * replayable decision or parameter revision. The replay engine orders by {@code seq}, never by
 * wall-clock insertion time.
 */
public enum EventType {
    MATERIAL_IMPORTED,   // immutable: a batch of source material became available
    SOURCE_PATCH,        // immutable-ish source change scoped to a local dependency
    PARAM_REVISION,      // a rule / parameter version changed (threshold, feature, window)
    LABEL_REMAP,         // cluster numbers were renumbered: identity must follow units
    MERGE,               // units merged
    SPLIT,               // a unit split; members may be shared (ambiguous) until adjudicated
    WINDOW_CHANGE,       // analysis time window moved: only intersecting clusters recompute
    ADJUDICATION,        // a human accepted / rejected / flagged evidence
    LOCAL_FAILURE        // a derivation failed for a bounded scope; neighbours remain valid
}
