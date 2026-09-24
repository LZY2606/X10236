package neurospike;

/** Immutable detection-threshold material (per channel rule + parameters). */
public record Threshold(String thresholdId, String rule, Quant factor,
                        String reference, String windowBasis) {
}
