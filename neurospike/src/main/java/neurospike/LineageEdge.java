package neurospike;

/**
 * Directed lineage edge between cluster generations. Cluster numbers are not
 * identities; these edges plus a content-derived stable id are the identity.
 */
public record LineageEdge(String fromClusterStableId, String toClusterStableId,
                          String edgeType, long seq, String eventId,
                          java.util.List<String> sharedDetections) {

    public static final String RENUMBER = "RENUMBER";
    public static final String MERGE = "MERGE";
    public static final String SPLIT = "SPLIT";
    public static final String CARRY = "CARRY";
}
