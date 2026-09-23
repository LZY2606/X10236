package org.neuro.gsb.domain;

/**
 * An imported multi-channel spike detection candidate. Time and amplitude keep
 * their original representation; {@code batchId} ties the event to the
 * threshold / detection parameters that produced it.
 */
public record DetectionEvent(String id, String batchId, String waveformRefId, String channelId,
                             Measure time, Measure amplitude, boolean valid, String invalidReason) {
}
