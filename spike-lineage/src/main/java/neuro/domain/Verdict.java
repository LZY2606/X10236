package neuro.domain;

/** Human adjudication outcomes. CONFLICT is a first-class state when decisions disagree. */
public enum Verdict {
    PENDING,
    ACCEPTED,
    REJECTED,
    FLAGGED,
    CONFLICT
}
