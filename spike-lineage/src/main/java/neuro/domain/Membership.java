package neuro.domain;

/**
 * Membership of one spike in one stable unit.
 * CONFIRMED membership is unique; SHARED means the spike legitimately belongs to more than one unit
 * after a split and no unique resolution exists, so every legal candidate is retained.
 */
public record Membership(String spikeId, MemberStatus status) {
    public enum MemberStatus { CONFIRMED, SHARED }
}
