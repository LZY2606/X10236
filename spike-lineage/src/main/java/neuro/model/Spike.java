package neuro.model;

/**
 * One detected multi-channel spike candidate (immutable material).
 *
 * @param id          stable content-derived identity of the detection
 * @param channel     acquisition channel id
 * @param timeS       detection time in seconds (detection time basis)
 * @param rawTime     original time representation preserved from the source
 * @param amplitude   amplitude with unit + basis (may be unconverted)
 * @param waveformRef reference to the raw waveform snippet (never inlined into derived layers)
 * @param windowS     snippet half-width in seconds
 * @param sourceFile  originating material file
 * @param sourceIndex row index inside the originating material file
 */
public record Spike(
        String id,
        String channel,
        double timeS,
        String rawTime,
        Quantity amplitude,
        String waveformRef,
        double windowS,
        String sourceFile,
        int sourceIndex) {
}
