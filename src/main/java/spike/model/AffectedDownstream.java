package spike.model;

import java.util.List;

/** What a decision or recomputation actually touched, keyed by stable identities. */
public record AffectedDownstream(List<String> runs, List<String> clusters, List<String> spikeIds,
                                List<String> evidences, List<String> lineages,
                                List<String> adjudications, String scopeReason) {
}
