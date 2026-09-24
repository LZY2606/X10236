package neuro.spike.model;

import java.util.List;

/**
 * Immutable reference to a waveform snippet. Samples are stored as their raw
 * representation (voltage per sample). A ref points at external material; the
 * snippet itself is never mutated, only referenced.
 */
public record WaveformRef(String waveformId,
                          String sourceRef,
                          String channel,
                          List<Double> samplesUv,
                          int peakIndexSamples,
                          Quantity sampleRate,
                          String timeBasis) {
    public WaveformRef {
        samplesUv = samplesUv == null ? List.of() : List.copyOf(samplesUv);
        if (peakIndexSamples < 0 || peakIndexSamples >= samplesUv.size()) {
            throw new IllegalArgumentException("peak index outside snippet: " + peakIndexSamples);
        }
    }

    public double peakAmplitudeUv() {
        // signed trough depth for a negative-going extracellular spike
        return samplesUv.get(peakIndexSamples);
    }

    public double peakOffsetSeconds() {
        return peakIndexSamples / sampleRate.value;
    }
}
