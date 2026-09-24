package spike.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A derived result. Provenance is explicit:
 * input material digest, parameter/rule version, parent results, compute budget.
 * {@code stableOrderKey} makes equivalent inputs sort identically.
 */
public record Result(
        String id,
        String kind,
        String branchId,
        long branchSeq,
        Outcome outcome,
        JsonNode value,
        String inputDigest,
        String paramsVersion,
        String depsDigest,
        long budgetMicros,
        String stableOrderKey,
        List<String> parentIds,
        List<String> materialIds,
        long producedSeq
) {
}
