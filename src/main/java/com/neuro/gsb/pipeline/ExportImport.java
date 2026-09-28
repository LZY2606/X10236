package com.neuro.gsb.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neuro.gsb.domain.Ids;
import com.neuro.gsb.domain.Result;
import com.neuro.gsb.store.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Archive export/import. The archive carries every table with stable ids and
 * event seqs; material content hashes are verified on import. Identity,
 * ordering and provenance relations survive a delete-database + re-import
 * round trip unchanged.
 */
public final class ExportImport {
    public static final String FORMAT = "pulse-cluster-spectrum-archive";
    public static final int FORMAT_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database db;
    public ExportImport(Database db) { this.db = db; }

    public String exportArchive() {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", FORMAT);
        root.put("format_version", FORMAT_VERSION);
        root.set("material", dump(java.util.Set.of("created_seq"),
                "SELECT id,kind,content,content_hash,source_name,created_seq FROM material ORDER BY id",
                "id", "kind", "content", "content_hash", "source_name", "created_seq"));
        root.set("event", dump(java.util.Set.of("seq"),
                "SELECT seq,id,kind,payload,parents,created_at FROM event ORDER BY seq",
                "seq", "id", "kind", "payload", "parents", "created_at"));
        root.set("unit_identity", dump(java.util.Set.of("origin_event_seq"),
                "SELECT identity,origin_event_seq,status,parent_identity FROM unit_identity ORDER BY identity",
                "identity", "origin_event_seq", "status", "parent_identity"));
        root.set("membership", dump(java.util.Set.of("assigned_event_seq"),
                "SELECT identity,event_key,label_set_version,cluster_label,assigned_event_seq FROM membership ORDER BY identity,event_key",
                "identity", "event_key", "label_set_version", "cluster_label", "assigned_event_seq"));
        root.set("evidence", dump(java.util.Set.of("id", "event_seq"),
                "SELECT id,identity,kind,payload,event_seq FROM evidence ORDER BY id",
                "id", "identity", "kind", "payload", "event_seq"));
        root.set("computation", dump(java.util.Set.of("created_event_seq", "invalidated_event_seq"),
                "SELECT id,kind,identity,input_digest,param_version,parents,payload,status,created_event_seq,invalidated_event_seq FROM computation ORDER BY id",
                "id", "kind", "identity", "input_digest", "param_version", "parents", "payload", "status", "created_event_seq", "invalidated_event_seq"));
        root.set("diagnostic", dump(java.util.Set.of("id", "event_seq"),
                "SELECT id,severity,position,message,event_seq FROM diagnostic ORDER BY id",
                "id", "severity", "position", "message", "event_seq"));
        root.set("import_session", dump(java.util.Set.of(),
                "SELECT id,status,detail FROM import_session ORDER BY id",
                "id", "status", "detail"));
        root.put("archive_hash", Ids.sha256(canonicalRows(root)));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String canonicalRows(ObjectNode root) {
        StringBuilder sb = new StringBuilder();
        root.fields().forEachRemaining(e -> {
            if (e.getValue() instanceof ArrayNode arr) sb.append(e.getKey()).append(arr.toString());
        });
        return sb.toString();
    }

    private ArrayNode dump(java.util.Set<String> numericCols, String sql, String... cols) {
        ArrayNode arr = JSON.createArrayNode();
        try (var st = db.conn().createStatement(); var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ObjectNode row = JSON.createObjectNode();
                for (String col : cols) {
                    String v = rs.getString(col);
                    if (v == null) row.putNull(col);
                    else if (numericCols.contains(col)) row.put(col, Long.parseLong(v));
                    else row.put(col, v);
                }
                arr.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return arr;
    }

    /** Import an archive into this (expected empty) database. Hash mismatch → FormatCorrupt. */
    public Result<List<String>> importArchive(String archiveJson) {
        JsonNode root;
        try {
            root = JSON.readTree(archiveJson);
        } catch (Exception e) {
            return new Result.FormatCorrupt<>("archive", "not valid JSON: " + e.getMessage());
        }
        if (!FORMAT.equals(root.path("format").asText()) || root.path("format_version").asInt() != FORMAT_VERSION) {
            return new Result.FormatCorrupt<>("archive", "unsupported format/version");
        }
        // verify material content hashes before writing anything
        List<String> problems = new ArrayList<>();
        for (JsonNode m : root.withArray("material")) {
            String expected = m.get("content_hash").asText();
            String actual = Ids.sha256(m.get("content").asText());
            if (!expected.equals(actual)) {
                problems.add("material " + m.get("id").asText() + " content hash mismatch");
            }
        }
        if (!problems.isEmpty()) {
            return new Result.FormatCorrupt<>("archive:material", String.join("; ", problems));
        }

        return db.inTransaction(conn -> {
            try {
                insertAll(conn, "INSERT INTO material(id,kind,content,content_hash,source_name,created_seq) VALUES(?,?,?,?,?,?)",
                        root.withArray("material"), "id", "kind", "content", "content_hash", "source_name", "created_seq");
                insertAll(conn, "INSERT INTO event(seq,id,kind,payload,parents,created_at) VALUES(?,?,?,?,?,?)",
                        root.withArray("event"), "seq", "id", "kind", "payload", "parents", "created_at");
                insertAll(conn, "INSERT INTO unit_identity(identity,origin_event_seq,status,parent_identity) VALUES(?,?,?,?)",
                        root.withArray("unit_identity"), "identity", "origin_event_seq", "status", "parent_identity");
                insertAll(conn, "INSERT INTO membership(identity,event_key,label_set_version,cluster_label,assigned_event_seq) VALUES(?,?,?,?,?)",
                        root.withArray("membership"), "identity", "event_key", "label_set_version", "cluster_label", "assigned_event_seq");
                insertAll(conn, "INSERT INTO evidence(id,identity,kind,payload,event_seq) VALUES(?,?,?,?,?)",
                        root.withArray("evidence"), "id", "identity", "kind", "payload", "event_seq");
                insertAll(conn, "INSERT INTO computation(id,kind,identity,input_digest,param_version,parents,payload,status,created_event_seq,invalidated_event_seq) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        root.withArray("computation"), "id", "kind", "identity", "input_digest", "param_version", "parents", "payload", "status", "created_event_seq", "invalidated_event_seq");
                insertAll(conn, "INSERT INTO diagnostic(id,severity,position,message,event_seq) VALUES(?,?,?,?,?)",
                        root.withArray("diagnostic"), "id", "severity", "position", "message", "event_seq");
                insertAll(conn, "INSERT INTO import_session(id,status,detail) VALUES(?,?,?)",
                        root.withArray("import_session"), "id", "status", "detail");
            } catch (SQLException e) {
                throw new IllegalStateException("archive import failed, rolled back", e);
            }
            List<String> imported = new ArrayList<>();
            root.withArray("unit_identity").forEach(n -> imported.add(n.get("identity").asText()));
            return Result.ok(imported);
        });
    }

    private void insertAll(java.sql.Connection conn, String sql, JsonNode rows, String... cols) throws SQLException {
        try (var ps = conn.prepareStatement(sql)) {
            for (JsonNode row : rows) {
                for (int i = 0; i < cols.length; i++) {
                    JsonNode v = row.get(cols[i]);
                    if (v == null || v.isNull()) ps.setNull(i + 1, java.sql.Types.VARCHAR);
                    else if (v.isNumber()) ps.setLong(i + 1, v.asLong());
                    else ps.setString(i + 1, v.asText());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
