package spike.model;

import java.util.List;

/**
 * Immutable reference to a raw waveform fragment. The raw representation (gain, unit, sample
 * rate) is preserved; samples are stored in the raw amplitude unit and only normalized to the
 * canonical microvolt scale inside the derived layer.
 */
public record WaveformRef(String id, String channelId, List<Double> samples, Quantity sampleRate,
                          Quantity gain, String rawUnit, int peakIndex, String storageUri) {
}
