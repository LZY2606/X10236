package neuro.domain;

import java.util.List;

/**
 * Evidence is advisory only. Refractory violations, amplitude drift and simultaneous multi-channel
 * propagation are collected as structured evidence; none of them re-identifies units and none is a
 * verdict. Verdicts live in adjudication events.
 */
public final class Evidence {
    private Evidence() {}

    public record RefractoryPair(String spikeA, String spikeB, String channel,
                                 double isiS, double refractoryS, String basis) {}

    public record AmplitudeDrift(String spikeFirst, String spikeLast, double firstAmpUv,
                                 double lastAmpUv, double ratio, String unit, String basis) {}

    public record Propagation(String anchorSpike, List<String> concurrentSpikes,
                              double spreadS, double channelSpanUm, String timeBasis) {}

    public record Bundle(List<RefractoryPair> refractory,
                         List<AmplitudeDrift> amplitudeDrift,
                         List<Propagation> propagation,
                         double firingRateHz,
                         int spikeCount,
                         double rateBasisStartS,
                         double rateBasisEndS) {}
}
