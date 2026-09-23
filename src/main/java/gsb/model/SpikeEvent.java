package gsb.model;

/** A detected spike event. Identity comes from the detector event id, never
 *  from any cluster index. */
public final class SpikeEvent implements Comparable<SpikeEvent> {
    public final String eventId;
    public final double tSeconds;      // normalized, basis "recording-clock"
    public final String channel;       // probe site id
    public final Quantity amplitude;   // unit uV
    public final Quantity widthMs;     // unit ms
    public final String segmentId;     // source waveform segment

    public SpikeEvent(String eventId, double tSeconds, String channel,
                      Quantity amplitude, Quantity widthMs, String segmentId) {
        this.eventId = eventId; this.tSeconds = tSeconds; this.channel = channel;
        this.amplitude = amplitude; this.widthMs = widthMs; this.segmentId = segmentId;
    }

    /** Deterministic order for equivalent inputs. */
    @Override public int compareTo(SpikeEvent o) {
        int c = Double.compare(tSeconds, o.tSeconds);
        if (c != 0) return c;
        c = channel.compareTo(o.channel);
        if (c != 0) return c;
        return eventId.compareTo(o.eventId);
    }
}
