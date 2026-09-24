package neurospike;

import java.util.List;

/** Immutable probe geometry material. */
public record Probe(String probeId, String name, String coordinateBasis,
                    Quant siteSpacing, List<ProbeSite> sites) {
}
