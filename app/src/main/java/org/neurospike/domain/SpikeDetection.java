package org.neurospike.domain;

public record SpikeDetection(
        String id,
        String waveformId,
        String thresholdId,
        String channelId,
        long timeMs,
        double amplitudeMicroV,
        String rawRepresentation,
        String status,
        String diagnostic) {
}
