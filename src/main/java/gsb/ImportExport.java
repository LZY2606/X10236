package gsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Export/import of the whole project as one JSON bundle. Identity (ids),
 * ordering (seq) and provenance (parents, digests) survive a delete+import
 * round trip byte-for-byte at the canonical level.
 */
public final class ImportExport {
    public enum Outcome { OK, FORMAT_CORRUPT, SEMANTIC_UNSOLVABLE }

    public static final class Result {
        public final Outcome outcome;
        public final String message;
        Result(Outcome o, String m) { outcome = o; message = m; }
    }

    private static final String[] TABLES = {
            "meta", "waveform_refs", "probe_geometries", "detection_thresholds",
            "feature_versions", "cluster_labels", "adjudication_events", "units",
            "derived_results", "evidence_events", "diagnostics", "write_intents"
    };

    private final Database db;

    public ImportExport(Database db) {
        this.db = db;
    }

    public ObjectNode exportBundle() {
        ObjectNode bundle = Json.obj();
        ObjectNode manifest = bundle.putObject("manifest");
        manifest.put("format", "spike-cluster-lineage/1");
        manifest.put("exportedSeq", db.currentSeq());
        ObjectNode tables = bundle.putObject("tables");
        for (String table : TABLES) {
            ArrayNode rows = tables.putArray(table);
            List<JsonNode> rs = db.query("SELECT * FROM " + table + " ORDER BY rowid", rs -> {
                ObjectNode row = Json.obj();
                int n = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= n; i++) {
                    String col = rs.getMetaData().getColumnName(i);
                    Object v = rs.getObject(i);
                    if (v == null) row.putNull(col);
                    else if (v instanceof Number num) row.putPOJO(col, num);
                    else row.put(col, v.toString());
                }
                return row;
            });
            rs.forEach(rows::add);
        }
        manifest.put("tableDigest", Json.digestOf(tables));
        return bundle;
    }

    public Result importBundle(String text) {
        JsonNode bundle;
        try {
            bundle = Json.parse(text);
        } catch (RuntimeException e) {
            return new Result(Outcome.FORMAT_CORRUPT, "bundle is not valid JSON: " + e.getMessage());
        }
        if (!bundle.isObject() || !bundle.has("manifest") || !bundle.has("tables")) {
            return new Result(Outcome.FORMAT_CORRUPT, "bundle lacks manifest/tables structure");
        }
        JsonNode manifest = bundle.get("manifest");
        if (!"spike-cluster-lineage/1".equals(manifest.path("format").asText())) {
            return new Result(Outcome.FORMAT_CORRUPT, "unknown format " + manifest.path("format").asText());
        }
        JsonNode tables = bundle.get("tables");
        if (!Json.digestOf(tables).equals(manifest.path("tableDigest").asText())) {
            return new Result(Outcome.SEMANTIC_UNSOLVABLE, "table digest mismatch: bundle was altered");
        }
        for (String table : TABLES) {
            if (!tables.has(table) || !tables.get(table).isArray()) {
                return new Result(Outcome.FORMAT_CORRUPT, "missing table " + table);
            }
        }
        try {
            db.txRun(d -> {
                for (String table : TABLES) {
                    d.execute("DELETE FROM " + table);
                    for (JsonNode row : tables.get(table)) {
                        insertRow(table, row);
                    }
                }
            });
        } catch (RuntimeException e) {
            return new Result(Outcome.SEMANTIC_UNSOLVABLE, "import failed: " + e.getMessage());
        }
        return new Result(Outcome.OK, "imported at seq " + db.currentSeq());
    }

    private void insertRow(String table, JsonNode row) {
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        java.util.List<Object> vals = new java.util.ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = row.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            if (cols.length() > 0) { cols.append(','); marks.append(','); }
            cols.append(f.getKey());
            marks.append('?');
            JsonNode v = f.getValue();
            if (v == null || v.isNull()) vals.add(null);
            else if (v.isIntegralNumber()) vals.add(v.asLong());
            else if (v.isNumber()) vals.add(v.asDouble());
            else vals.add(v.asText());
        }
        db.execute("INSERT INTO " + table + "(" + cols + ") VALUES(" + marks + ")", vals.toArray());
    }

    /** Canonical dump of every table, used to verify round-trip equality. */
    public String canonicalDump() {
        return Json.canonical(exportBundle().get("tables"));
    }
}
