package neurospike;

import java.util.List;

/**
 * Project export bundle. Everything needed to restore identity, ordering and
 * provenance relationships: original case pack, the causal event log, decisions
 * and finding adoption history. Canonical byte order makes repeated exports
 * byte-comparable.
 */
public record ExportBundle(String format, String caseId, CasePack baseline,
                           List<Event> events, List<Decision> decisions,
                           List<FindingLedgerEntry> findingLedger,
                           List<String> recoveryNotes) {

    public static final String FORMAT = "neurospike/project-export/v1";
}
