package neurospike;

import java.util.List;

/** Immutable adjudication material that ships inside the initial case pack. */
public record AdjudicationSeed(String eventId, String atSample, String targetId,
                               String targetKind, String verdict, String rationale,
                               String reviewer, List<String> candidates) {
}
