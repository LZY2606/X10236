package neuro.model;

/**
 * Probe geometry for one channel. Coordinates carry a basis label. A malformed geometry entry keeps
 * its raw text and a diagnostic rather than being coerced to (0,0).
 */
public record ChannelGeometry(
        String channel,
        Double xUm,
        Double yUm,
        String basis,
        String raw,
        String diagnostic) {

    public boolean usable() { return xUm != null && yUm != null; }
}
