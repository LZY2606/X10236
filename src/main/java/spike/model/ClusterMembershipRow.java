package spike.model;

import spike.model.Enums.Membership;

/**
 * One spike's membership in one cluster for one run. A spike that cannot be uniquely assigned
 * after a split is stored as CANDIDATE on every legal child rather than forced to one.
 */
public record ClusterMembershipRow(String runId, String clusterId, int clusterNumber,
                                   String spikeId, Membership membership,
                                   long sinceSeq, Long untilSeq) {
}
