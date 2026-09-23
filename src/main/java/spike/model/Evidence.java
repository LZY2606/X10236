package spike.model;

import java.util.List;
import spike.model.Enums.EvidenceType;

/**
 * Evidence is exactly that: refractory violations, amplitude drift and synchronous multi-channel
 * propagation are observations, never a stable identity by themselves.
 */
public record Evidence(String id, EvidenceType type, List<String> subjectClusterIds,
                       List<String> spikeIds, String detail, double magnitude, String unit,
                       String runId, long sinceSeq, boolean valid, String invalidReason) {
}
