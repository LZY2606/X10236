package org.neurospike.ingest;

import org.neurospike.model.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 极简 RFC4180 风格 CSV（支持引号与逗号），返回带列名的行，便于带位置诊断。 */
public final class Csv {
    private Csv() {}

    public record Table(List<String> header, List<Row> rows) {}
    public static final class Row {
        public final int lineNo;
        private final Map<String, String> values;
        Row(int lineNo, Map<String, String> values) { this.lineNo = lineNo; this.values = values; }
        public String get(String col) { return values.get(col); }
        public Map<String, String> all() { return values; }
    }

    public static Table parse(String content, String path, List<Diagnostic> diagnostics) {
        String[] lines = content.split("\r?\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            diagnostics.add(Diagnostic.of("corrupt-empty", path, "CSV has no header"));
            return new Table(List.of(), List.of());
        }
        List<String> header = parseLine(lines[0]);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> cells = parseLine(lines[i]);
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                map.put(header.get(c), c < cells.size() ? cells.get(c) : null);
            }
            rows.add(new Row(i + 1, map));
        }
        return new Table(header, rows);
    }

    public static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else cur.append(ch);
            } else if (ch == ',') {
                out.add(cur.toString()); cur.setLength(0);
            } else if (ch == '"') {
                quoted = true;
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    public static Diagnostic requireColumn(Csv.Table t, String col, String path) {
        return t.header().contains(col) ? null
                : Diagnostic.of("corrupt-missing-column", path + "#header", "missing column: " + col);
    }
}
