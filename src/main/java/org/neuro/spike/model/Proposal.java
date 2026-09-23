package org.neuro.spike.model;

import java.util.List;

/**
 * A proposed cluster operation awaiting adjudication. When multiple distinct
 * legal groupings exist {@code candidates} carries all of them and the result
 * is UNDECIDABLE until a human chooses one.
 */
public record Proposal(
        String uid,
        String kind,
        List<String> clusterUids,
        List<List<String>> candidates,
        String detailJson,
        String status,
        int seq) {
}
