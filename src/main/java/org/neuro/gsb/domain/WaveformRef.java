package org.neuro.gsb.domain;

import java.util.List;

/**
 * Raw waveform fragment. The reference (source URI) is the identity; the inline
 * ADC samples are the immutable payload. Invalid fragments are never silently
 * dropped: {@link #valid()} is false with an {@link #invalidReason()}.
 */
public record WaveformRef(String id, String sourceUri, String recordingId, String channelId,
                          Measure startTime, Measure samplingRate, Measure gain,
                          List<Integer> adcSamples, boolean valid, String invalidReason) {

    public int sampleCount() {
        return adcSamples == null ? 0 : adcSamples.size();
    }
}
