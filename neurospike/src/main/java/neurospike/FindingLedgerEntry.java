package neurospike;

/** One persisted finding revision (adopted and not-adopted results both kept). */
public record FindingLedgerEntry(String findingId, int revision, String type,
                                 String clusterStableId, String status,
                                 String introducedEvent, String validFromEvent,
                                 String validToEvent, String adopted, String json) {
}
