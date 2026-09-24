package neuro.spike.model;

/**
 * Immutable material: the detection configuration that produced spike events.
 * The value keeps its raw text; units (e.g. uV or MAD sigma) are explicit.
 */
public record DetectionThreshold(String materialId,
                                 String sourceRef,
                                 String detector,
                                 Quantity value,
                                 String thresholdBasis,
                                 Quantity sampleRate,
                                 double refractorySeconds) {
    public DetectionThreshold {
        if (refractorySeconds <= 0) throw new IllegalArgumentException("refractory period must be > 0");
        if (thresholdBasis == null || thresholdBasis.isBlank())
            throw new IllegalArgumentException("threshold basis required");
    }
}
