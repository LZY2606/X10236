package neuro.spike.model;

/** Categories of computed evidence. None of these grant stable identity. */
public enum EvidenceKind {
    CONCURRENT_SPIKES("concurrent_spikes"),
    AMPLITUDE_DRIFT("amplitude_drift"),
    REFRACTORY_VIOLATION("refractory_violation"),
    MULTI_CHANNEL_PROPAGATION("multi_channel_propagation"),
    AUTOCORRELATION("autocorrelation"),
    FIRING_RATE("firing_rate"),
    FEATURE_PROJECTION("feature_projection");

    public final String code;
    EvidenceKind(String code) { this.code = code; }
}
