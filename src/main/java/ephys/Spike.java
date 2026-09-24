package ephys;

/** An imported detection event. Normalized values may be null when the raw
 *  quantity could not be converted; the raw representation is always kept. */
public record Spike(String id, String channel, Double tMs, Double ampUv,
                    String tRaw, String ampRaw, String waveRef, int ord) {
    public boolean usable() {
        return tMs != null && ampUv != null;
    }
}
