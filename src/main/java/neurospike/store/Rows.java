package neurospike.store;

import java.util.List;

/** Row types for the layered storage. Materials are immutable + seq-versioned. */
public final class Rows {
    private Rows() {}

    public record WaveformRef(String id, String eventId, int channel,
                              String tRaw, String tUnit, List<Double> samplesRaw, String sampleUnit,
                              String fsRaw, String fsUnit,
                              Double tMs, List<Double> samplesUv, Double fsHz,
                              String normStatus, String digest) {}

    public record ProbePoint(String id, int version, int channel,
                             Double xUm, Double yUm, String basis, String normStatus) {}

    public record DetectionParam(String id, int version,
                                 Double thresholdUv, Double refractoryMs, String normStatus) {}

    public record FeatureSet(String id, String featureVersion, String eventId, List<Double> components) {}

    public record ClusterLabel(String id, int version, String eventId, int clusterNo, String unitUid) {}

    public record DecisionEvent(String id, long seq, String kind, String subjectUid,
                                String actor, String rationale, String payload,
                                String supersedes, String status) {}

    public record DerivedResult(String id, String kind, String subjectUid,
                                String inputDigest, int paramVersion, List<String> parentIds,
                                String payload, String status, String invalidationReason,
                                long seqCreated, Long seqInvalidated) {}

    /** One spike of a unit, joined label+waveform, in stable order. */
    public record UnitSpike(String eventId, String unitUid, int clusterNo, int channel,
                            double tMs, double peakUv, List<Double> samplesUv, Double fsHz) {}
}
