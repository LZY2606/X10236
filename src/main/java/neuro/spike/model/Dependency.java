package neuro.spike.model;

import java.util.List;

/**
 * Pointer to a parent input for a derived result. The digest makes every
 * derived value replayable: same inputs + same rule version => same result id.
 */
public record Dependency(String parentId, String materialKind, String contentDigest, int revision) {
    public static String digestOf(List<Dependency> deps) {
        StringBuilder sb = new StringBuilder();
        deps.stream().map(Dependency::contentDigest).sorted().forEachOrdered(d -> sb.append(d).append('|'));
        return neuro.spike.provenance.Ids.hash(sb.toString());
    }
}
