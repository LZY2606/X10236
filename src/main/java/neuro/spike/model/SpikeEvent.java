package neuro.spike.model;

/**
 * Immutable material: one detected candidate spike event.
 * Time is stored both in its original representation and canonicalised to
 * seconds on the session time basis. Detection is multi-channel, so a single
 * event may be observed on several channels (simultaneous propagation).
 */
public record SpikeEvent(String eventId,
                         String sourceRef,
                         Quantity rawTime,
                         double timeSeconds,
                         String primaryChannel,
                         String waveformId,
                         long importOrdinal) {

    public SpikeEvent {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("event id required");
        if (primaryChannel == null) throw new IllegalArgumentException("channel required for " + eventId);
        if (timeSeconds < 0) throw new IllegalArgumentException("negative canonical time for " + eventId);
    }
}
