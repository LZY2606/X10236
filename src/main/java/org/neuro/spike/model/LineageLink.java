package org.neuro.spike.model;

import java.util.List;

/**
 * One provenance relation linking the previous snapshot clusters to a
 * current cluster (or children for a split). Shared events after a split
 * are captured explicitly so the ambiguity is evidence, not an identity merge.
 */
public record LineageLink(
        String uid,
        LineageRelation relation,
        List<String> parentClusters,
        List<String> childClusters,
        List<String> sharedEvents,
        String rationale,
        int seq) {
}
