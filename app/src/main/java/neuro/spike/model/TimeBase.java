package neuro.spike.model;

/**
 * Recording time base: all spike times are sample indices interpreted with this origin.
 * Times in derived layers are seconds relative to {@code originIso}.
 */
public record TimeBase(String originIso, double sampleRateHz, long firstSample, String id) {
    public double sampleToSeconds(long sample) {
        return (sample - firstSample) / sampleRateHz;
    }
}
