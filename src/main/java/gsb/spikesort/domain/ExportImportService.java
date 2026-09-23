package gsb.spikesort.domain;

import com.fasterxml.jackson.databind.JsonNode;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Full-fidelity export/import. Identity, branches and decision history are
 *  preserved verbatim. Export writes to a temp file then atomically renames
 *  so a crash mid-write never corrupts an existing export. Import runs in a
 *  single transaction: any failure rolls back and the original database is
 *  untouched (half-write recovery). */
public final class ExportImportService {
    public static final String FORMAT = "pulse-cluster-spectrum-export";
    public static final int FORMAT_VERSION = 1;

    private final Store store;

    public ExportImportService(Store store) { this.store = store; }

    public String exportJson() {
        var root = Json.obj();
        var tables = Json.obj();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : store.tableNames()) {
            List<JsonNode> rows = store.dumpTable(table);
            counts.put(table, rows.size());
            var arr = Json.MAPPER.createArrayNode();
            rows.forEach(arr::add);
            tables.set(table, arr);
        }
        var manifest = Json.obj();
        manifest.put("format", FORMAT);
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("exportedAt", Instant.now().toString());
        manifest.set("tableCounts", Json.MAPPER.valueToTree(counts));
        manifest.put("digest", Json.digestOf(Json.canonical(tables)));
        root.set("manifest", manifest);
        root.set("tables", tables);
        return Json.write(root);
    }

    /** Atomic file export: tmp + rename. */
    public void exportToFile(Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, exportJson());
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public IngestResult importJson(String jsonText) {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(jsonText);
        } catch (Exception e) {
            return new IngestResult(IngestStatus.FORMAT_CORRUPT, List.of(
                    new gsb.spikesort.model.Model.Diagnostic("diag-import", "import", "$",
                            Severity.ERROR, "FORMAT_CORRUPT",
                            "export document is not valid JSON: " + e.getMessage(), null)), 0);
        }
        JsonNode manifest = root.path("manifest");
        if (!FORMAT.equals(manifest.path("format").asText())) {
            return new IngestResult(IngestStatus.FORMAT_CORRUPT, List.of(
                    new Diagnostic("diag-import", "import", "$.manifest.format",
                            Severity.ERROR, "FORMAT_CORRUPT",
                            "not a " + FORMAT + " document", manifest.path("format").asText())), 0);
        }
        if (manifest.path("formatVersion").asInt() != FORMAT_VERSION) {
            return new IngestResult(IngestStatus.SEMANTIC_UNSOLVABLE, List.of(
                    new Diagnostic("diag-import", "import", "$.manifest.formatVersion",
                            Severity.ERROR, "UNSUPPORTED_VERSION",
                            "unsupported export format version", manifest.path("formatVersion").asText())), 0);
        }
        JsonNode tables = root.path("tables");
        if (!tables.isObject()) {
            return new IngestResult(IngestStatus.FORMAT_CORRUPT, List.of(
                    new Diagnostic("diag-import", "import", "$.tables",
                            Severity.ERROR, "FORMAT_CORRUPT", "missing tables object", null)), 0);
        }
        // verify integrity digest before touching the database
        String expected = manifest.path("digest").asText();
        if (!expected.equals(Json.digestOf(Json.canonical(tables)))) {
            return new IngestResult(IngestStatus.FORMAT_CORRUPT, List.of(
                    new Diagnostic("diag-import", "import", "$.manifest.digest",
                            Severity.ERROR, "DIGEST_MISMATCH",
                            "export digest mismatch; document was truncated or modified", null)), 0);
        }

        store.begin();
        try {
            for (String table : store.tableNames()) store.clearTable(table);
            int rows = 0;
            for (String table : store.tableNames()) {
                for (JsonNode row : tables.path(table)) {
                    store.insertRow(table, row);
                    rows++;
                }
            }
            store.commit();
            return new IngestResult(IngestStatus.OK, List.of(), rows);
        } catch (Exception e) {
            store.rollbackQuietly();
            return new IngestResult(IngestStatus.SEMANTIC_UNSOLVABLE, List.of(
                    new Diagnostic("diag-import", "import", "$.tables",
                            Severity.ERROR, "SEMANTIC_UNSOLVABLE",
                            "import rolled back: " + e.getMessage(), null)), 0);
        }
    }
}
