package com.neuro.gsb.domain;

/**
 * One detected spike candidate as normalized in the derived layer.
 * The raw quantities stay on the material record; this view exists only
 * after successful conversion. Unconvertible members never reach here —
 * they remain on the raw record with a diagnostic.
 */
public record SpikeEvent(
        String eventKey,        // deterministic key from source, e.g. "det-0007"
        int channel,
        double timeMs,          // canonical, RECORDING_CLOCK basis
        double amplitudeUv,     // canonical
        String clusterLabel,    // sorter label at a given label-set version — NOT an identity
        String labelSetVersion
) implements Comparable<SpikeEvent> {

    /** Deterministic order for equivalent inputs: time, channel, then key. */
    @Override public int compareTo(SpikeEvent o) {
        int c = Double.compare(timeMs, o.timeMs);
        if (c != 0) return c;
        c = Integer.compare(channel, o.channel);
        return eventKey.compareTo(o.eventKey);
    }
}
