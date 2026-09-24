package neurospike;

import java.util.List;

/**
 * Reference to a raw waveform snippet plus its immutable sample values in the
 * original amplitude unit. The reference/uri is the material; samples here are
 * the imported original representation - normalization happens only in derived
 * features.
 */
public record WaveformRef(String waveformId, String uri, String sha256,
                          Quant startSample, Quant sampleCount, Quant samplingHz,
                          Quant amplitudeUnit, String channelId, String timeBasisId,
                          Integer peakIndex, List<Double> samples) {
}
