package org.neuro.spike.engine;

import org.neuro.spike.model.Quantity;

import java.util.List;

/** Detection event as seen by the derivation layer, with raw and SI views kept together. */
public record DetEvent(
        String uid,
        double timeSec,
        String timeRaw,
        int channel,
        Quantity amplitude,
        String waveformRef,
        boolean corrupt,
        String corruptionReason,
        boolean excluded,
        String excludeReason) {

    public boolean usableForFeatures() {
        return !corrupt && !excluded;
    }

    /** Probe channel coordinate entry, micrometres relative to probe origin. */
    public record Channel(int channel, double yUm, String xUm, String yRaw) {
    }

    public record Geometry(String frame, List<Channel> channels) {
    }
}
