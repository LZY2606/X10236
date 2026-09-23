package spike.model;

/**
 * The append-only replay log. Every state-changing action is one row with deterministic seq and
 * a parent pointer, so any historical state is reconstructed by replaying to that seq instead of
 * being overwritten by the newest computation.
 */
public record LedgerEvent(long seq, long parentSeq, String kind, String subjectId,
                          String payloadJson, String timeBasis, String actor, String ruleVersion) {
}
