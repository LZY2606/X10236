package neuro.spike.model;

import java.util.List;

/**
 * Immutable detection threshold revision. Threshold value keeps its raw representation;
 * polarity and basis are explicit.
 */
public record DetectThreshold(String id, String revision, String polarity,
                              Quantity value, String basis,
                              List<neuro.spike.util.Diagnostic> diagnostics) {
}
