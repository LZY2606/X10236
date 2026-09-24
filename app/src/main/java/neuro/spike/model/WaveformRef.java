package neuro.spike.model;

/**
 * Immutable reference to a raw waveform snippet. Samples live outside this application;
 * only the reference (uri) plus the index/coordinate basis is retained. {@code samples}
 * may be embedded for the offline case pack, each entry in microvolts or null where the
 * imported sample was unconvertible (never silently dropped).
 */
public record WaveformRef(String id, String uri, int channelIndex,
                          int preSamples, int postSamples, String unit,
                          java.util.List<String> samples,
                          java.util.List<neuro.spike.util.Diagnostic> diagnostics) {
}
