package spike.model;

import java.util.List;

/**
 * A detected spike candidate on one or more channels (multi-channel propagation gives several
 * channel ids). Time is stored both as the raw sample index (raw representation) and seconds on
 * the recording time basis (derived). amplitude carries a unit and valid range.
 */
public record SpikeEvent(String id, List<String> channelIds, long sampleIndex, Quantity timeSec,
                         Quantity amplitude, String waveformRefId, String thresholdId,
                         List<Double> features, String featureVersionId, boolean valid,
                         String invalidReason) {
}
