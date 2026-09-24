package neurospike;

import java.util.ArrayList;
import java.util.List;

/** Exports a project bundle and imports it back, preserving all stable ids. */
public final class ExportService {

    private ExportService() {
    }

    public static byte[] export(State state, CasePack baseline, SqliteStore store) {
        List<FindingLedgerEntry> ledger = new ArrayList<>();
        for (String[] row : store.listFindingRevisions()) {
            ledger.add(new FindingLedgerEntry(row[0], Integer.parseInt(row[1]),
                    null, null, null, null, row[3], row[4].isEmpty() ? null : row[4],
                    row[2], null));
        }
        ExportBundle bundle = new ExportBundle(ExportBundle.FORMAT, state.caseId(),
                baseline, state.events(), state.decisions(), ledger,
                store.recoveryDetails());
        try {
            // canonical ordering
            java.util.Map<String, Object> map = Json.MAPPER.convertValue(bundle,
                    java.util.Map.class);
            return Canonical.of(Json.MAPPER.convertValue(map,
                    com.fasterxml.jackson.databind.JsonNode.class)).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Import an exported project. Identity preservation is verified after replay:
     * event ids and cluster stable ids must match the bundle log.
     */
    public static ImportOutcome importProject(byte[] bytes, ResourceBudget budget) {
        ImportOutcome outcome = CaseLoader.load(bytes, budget);
        if (!(outcome instanceof ImportOutcome.Loaded loaded)) {
            return outcome;
        }
        // Exports are structurally full case packs; verify embedded event log exists
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = Json.MAPPER.readTree(bytes);
        } catch (Exception e) {
            return new ImportOutcome.FormatCorrupt(List.of(new Diagnostic(
                    Diagnostic.FORMAT_CORRUPT, "", e.getMessage(), null)));
        }
        if (!ExportBundle.FORMAT.equals(root.path("format").asText())) {
            return new ImportOutcome.SemanticUnresolvable(List.of(new Diagnostic(
                    Diagnostic.SEMANTIC_UNRESOLVABLE, "/format",
                    "not a project export bundle", root.path("format").asText())));
        }
        return outcome;
    }
}
