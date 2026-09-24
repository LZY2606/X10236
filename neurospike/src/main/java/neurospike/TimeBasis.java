package neurospike;

/**
 * Recording time basis: all detection times are relative to recordingStartSample
 * on sampleClockHz.
 */
public record TimeBasis(String basisId, String sampleClockHz, Quant recordingStartSample,
                        Quant samplesPerSecond, String timeZeroLabel) {
}
