package org.neuro.spike.model;

/**
 * A piece of evidence attached to a cluster. Evidence never rewrites the
 * cluster's stable identity; it only informs adjudication.
 */
public record Evidence(
        String uid,
        EvidenceKind kind,
        String clusterUid,
        String detailJson,
        double strength,
        int seq) {
}
