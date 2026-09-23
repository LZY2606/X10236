package spike.model;

import java.util.List;
import spike.model.Enums.LineageType;

/**
 * Provenance edge between clusters across runs. This — not the volatile display number — is how
 * the stable unit identity is followed through merge / split / drift / renumbering.
 */
public record LineageLink(String id, LineageType type, String fromClusterId, String toClusterId,
                          String fromRunId, String toRunId, List<String> evidenceIds,
                          long sinceSeq, String note) {
}
