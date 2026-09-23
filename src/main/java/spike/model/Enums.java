package spike.model;

public final class Enums {
    private Enums() {
    }

    public enum EvidenceType {
        REFRACTORY_VIOLATION,
        AMPLITUDE_DRIFT,
        SYNCHRONOUS_PROPAGATION
    }

    public enum LineageType {
        MERGE,
        SPLIT,
        DRIFT,
        SAME
    }

    public enum DecisionType {
        ACCEPT,
        REJECT,
        MERGE,
        SPLIT,
        PARAM_ADJUST,
        RESOLVE
    }

    public enum Membership {
        PRIMARY,
        CANDIDATE
    }

    public enum EventStatus {
        ADOPTED,
        REJECTED,
        CONFLICT,
        SUPERSEDED
    }
}
