package neuro.spike.model;

import java.util.List;

/**
 * Immutable multi-channel detection event. Times are sample indices against a declared
 * time-base reference; peakAmplitude preserves the raw token.
 */
public record DetectionEvent(String id, String timeBaseId, long sampleIndex,
                             String waveformRefId, Quantity peakAmplitude,
                             List<Integer> activeChannels,
                             List<neuro.spike.util.Diagnostic> diagnostics) {
}
