package neurogsb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import neurogsb.db.Database;
import neurogsb.model.OpResult;

import java.sql.SQLException;
import java.util.List;

/** 导出/导入：身份、分支与决定历史保持；格式损坏与语义无解区分返回。 */
public final class ExportService {
    public static final int FORMAT_VERSION = 1;
    private final Database db;
    private final Store store;

    public ExportService(Database db, Store store) { this.db = db; this.store = store; }

    private static final List<String> TABLES = List.of(
            "materials", "spike_events", "adjudication_events", "derived_results",
            "invalidations", "candidates", "diagnostics", "op_journal", "kv");

    public ObjectNode export() {
        ObjectNode root = Store.M.createObjectNode();
        root.put("format", "neuro-gsb-export");
        root.put("format_version", FORMAT_VERSION);
        ObjectNode tables = root.putObject("tables");
        for (String table : TABLES) {
            ArrayNode rows = tables.putArray(table);
            try (var ps = db.conn().prepareStatement("SELECT * FROM " + table);
                 var rs = ps.executeQuery()) {
                var md = rs.getMetaData();
                while (rs.next()) {
                    ObjectNode row = rows.addObject();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String col = md.getColumnName(i);
                        Object v = rs.getObject(i);
                        if (v == null) row.putNull(col);
                        else if (v instanceof Double || v instanceof Float) row.put(col, ((Number) v).doubleValue());
                        else if (v instanceof Number) row.put(col, ((Number) v).longValue());
                        else row.put(col, v.toString());
                    }
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
        return root;
    }

    /** 导入到空库或替换现有库。身份、顺序与来源关系逐行保留。 */
    public OpResult importInto(JsonNode root) {
        if (root == null || !root.hasNonNull("format") || !"neuro-gsb-export".equals(root.get("format").asText())) {
            return new OpResult.FormatCorrupt("format", "不是 neuro-gsb 导出文件");
        }
        if (!root.hasNonNull("format_version")) {
            return new OpResult.FormatCorrupt("format_version", "缺少格式版本");
        }
        if (root.get("format_version").asInt() != FORMAT_VERSION) {
            return new OpResult.SemanticUnsolvable("format_version",
                    "不支持的格式版本 " + root.get("format_version").asInt());
        }
        JsonNode tables = root.get("tables");
        if (tables == null || !tables.isObject()) {
            return new OpResult.FormatCorrupt("tables", "缺少 tables");
        }
        String opId = Store.newId("op");
        db.beginOp(opId, "import export");
        try {
            for (String table : TABLES) {
                JsonNode rows = tables.get(table);
                if (rows == null) continue;
                if (!rows.isArray()) return new OpResult.FormatCorrupt("tables." + table, "应为数组");
                for (JsonNode row : rows) {
                    insertRow(table, row);
                }
            }
            db.commitOp(opId);
            return new OpResult.Ok("imported", List.of(opId));
        } catch (RuntimeException e) {
            db.recover();
            return new OpResult.FormatCorrupt("tables", e.getMessage());
        }
    }

    private void insertRow(String table, JsonNode row) {
        var cols = new StringBuilder();
        var marks = new StringBuilder();
        var values = new java.util.ArrayList<JsonNode>();
        row.fields().forEachRemaining(e -> {
            cols.append(cols.length() == 0 ? "" : ",").append(e.getKey());
            marks.append(marks.length() == 0 ? "" : ",").append("?");
            values.add(e.getValue());
        });
        String sql = "INSERT INTO " + table + "(" + cols + ") VALUES(" + marks + ")";
        try (var ps = db.conn().prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) {
                JsonNode v = values.get(i);
                if (v == null || v.isNull()) ps.setObject(i + 1, null);
                else if (v.isIntegralNumber()) ps.setLong(i + 1, v.longValue());
                else if (v.isFloatingPointNumber()) ps.setDouble(i + 1, v.doubleValue());
                else ps.setString(i + 1, v.asText());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("insert " + table + ": " + e.getMessage(), e);
        }
    }
}
