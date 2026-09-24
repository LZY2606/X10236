package neuro.spike.model;

/** Human adjudication actions. These are the only mutable layer. */
public enum DecisionType {
    ACCEPT,       // accept a unit / evidence as a real single unit
    REJECT,       // reject (noise / multi-unit)
    MERGE,        // merge two unit keys into one
    SPLIT,        // split a unit into children
    PARAMETER_REVISION, // change a parameter (feature version / threshold / window)
    NOTE
}
