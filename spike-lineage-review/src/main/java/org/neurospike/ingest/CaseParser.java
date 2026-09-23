package org.neurospike.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import org.neurospike.domain.Diagnostic;
import org.neurospike.domain.Json;
import org.neurospike.domain.Models.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Parser for the domain-specific offline case pack. Three failure classes are kept apart:
 * CORRUPT_FORMAT (malformed tokens/columns), SEMANTIC_UNRESOLVABLE (valid syntax, unknown
 * meaning) and BUDGET_EXHAUSTED (row/byte caps). Unconvertible quantities are retained.
 */
public class CaseParser {
    private final int maxRowsPerFile;
    private final long maxBytesPerFile;

    public CaseParser(int maxRowsPerFile, long maxBytesPerFile) {
        this.maxRowsPerFile = maxRowsPerFile;
        this.maxBytesPerFile = maxBytesPerFile;
    }

    public ParsedCase parse(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new ParseException("case directory missing: " + dir,
                    List.of(org.neurospike.domain.Diagnostic.of
                            (org.neurospike.domain.Diagnostic.CORRUPT_FORMAT, "casepack", 0, 0,
                                    "missing directory", null, Map.of("path", dir.toString()))));
        }
        Map<String, Object> manifest = parseJson(readRequired(dir, "manifest.json"),
                new TypeReference<Map<String, Object>>() {
                }, "manifest.json");
        Map<String, Object> rules = parseJson(readRequired(dir, "rules.json"),
                new TypeReference<Map<String, Object>>() {
                }, "rules.json");
        List<Map<String, Object>> scenarios = parseJson(readRequired(dir, "scenarios.json"),
                new TypeReference<List<Map<String, Object>>>() {
                }, "scenarios.json");

        Map<String, WaveformRef> waveforms = new LinkedHashMap<>();
        Map<String, List<Site>> sites = new LinkedHashMap<>();
        for (Line ln : readLines(dir, "waveforms.wvr")) {

            String[] f = split(ln, 11);
            Quant gain = QuantParser.parse(f[10], "uV/count", null);
            WaveformRef ref = new WaveformRef(f[0], f[1], f[2], f[3], f[4], f[5],
                    parseDouble(ln, f[6], 6, "Hz"), parseInt(ln, f[7], 7),
                    parseLong(ln, f[8], 8), parseInt(ln, f[9], 9), List.of(gain));
            waveforms.put(ref.refId(), ref);
            sites.put(ref.refId(), new ArrayList<>());
        }
        for (Line ln : readLines(dir, "geometry.geo")) {

            String[] f = split(ln, 5);
            String refId = f[0];
            if (!waveforms.containsKey(refId)) {
                throw new SemanticException("geometry references unknown waveform",
                        List.of(DiagnosticBuilder.semantic("geometry.geo", ln.no, 1,
                                "unknown waveform ref " + refId, refId)));
            }
            Quant x = QuantParser.parse(f[2], "um", "probe:" + refId);
            Quant y = QuantParser.parse(f[3], "um", "probe:" + refId);
            Quant z = QuantParser.parse(f[4], "um", "probe:" + refId);
            sites.get(refId).add(new Site(parseInt(ln, f[1], 1), x, y, z, "probe:" + refId));
        }
        List<Threshold> thresholds = new ArrayList<>();
        for (Line ln : readLines(dir, "thresholds.thr")) {

            String[] f = split(ln, 5);
            Quant v = QuantParser.parse(f[2], "sigma", null);
            thresholds.add(new Threshold(f[0], parseInt(ln, f[1], 1), v, f[3], f[4]));
        }
        List<SpikeEvent> spikes = new ArrayList<>();
        for (Line ln : readLines(dir, "spikes.spk")) {

            String[] f = split(ln, 8);
            long eventId = parseLong(ln, f[0], 0);
            Quant t = QuantParser.parse(f[1], "ms", "recclock");
            int primary = parseInt(ln, f[2], 2);
            String refId = f[3];
            if (!waveforms.containsKey(refId)) {
                throw new SemanticException("spike references unknown waveform",
                        List.of(DiagnosticBuilder.semantic("spikes.spk", ln.no, 4,
                                "unknown waveform ref " + refId, refId)));
            }
            long sampleIndex = parseLong(ln, f[4], 4);
            int sampleLength = parseInt(ln, f[5], 5);
            List<Integer> channels = parseChannelList(ln, f[6]);
            Map<String, Quant> meas = new LinkedHashMap<>();
            Quant amp = QuantParser.parse(f[7], "uV", null);
            meas.put("amplitude", amp);
            spikes.add(new SpikeEvent(eventId, t, primary, refId, sampleIndex, sampleLength,
                    meas, channels, t.convertible()));
        }
        Map<String, ParsedCase.FeatureRow> features = new LinkedHashMap<>();
        for (Line ln : readLines(dir, "features.ftr")) {

            String[] f = split(ln, 6);
            String featureId = f[0];
            long eventId = parseLong(ln, f[1], 1);
            String version = f[2];
            String basis = f[3];
            Quant pc1 = QuantParser.parse(f[4], "std", "pc1@" + version);
            Quant pc2 = QuantParser.parse(f[5], "std", "pc2@" + version);
            Map<String, Quant> values = new LinkedHashMap<>();
            values.put("pc1", pc1);
            values.put("pc2", pc2);
            String diag = !pc1.convertible() ? pc1.diagnostic()
                    : (!pc2.convertible() ? pc2.diagnostic() : null);
            features.put(featureId, new ParsedCase.FeatureRow(featureId, eventId, version, basis,
                    values, diag));
        }
        List<ParsedCase.ClusterFile> clusterFiles = new ArrayList<>();
        for (Line ln : readLines(dir, "clusters.clu")) {

            if (ln.text.startsWith("## ")) {
                String hdr = ln.text.substring(3);
                Map<String, Object> h = parseJson(hdr,
                        new TypeReference<Map<String, Object>>() {
                        }, "clusters.clu@" + ln.no);
                ParsedCase.Header header = new ParsedCase.Header(
                        (String) h.get("clusterId"), (String) h.get("version"),
                        ((Number) h.getOrDefault("seq", 0)).intValue(),
                        (String) h.get("featureVersion"), (String) h.get("thresholdId"),
                        (String) h.get("windowStart"), (String) h.get("windowEnd"),
                        h.containsKey("params") ? castMap(h.get("params")) : Map.of());
                clusterFiles.add(new ParsedCase.ClusterFile(header, new ArrayList<>()));
            } else {
                if (clusterFiles.isEmpty()) {
                    throw new ParseException("cluster row before header",
                            List.of(DiagnosticBuilder.corrupt("clusters.clu", ln.no, 1,
                                    "assignment row without ## header", ln.text)));
                }
                String[] f = split(ln, 4);
                clusterFiles.get(clusterFiles.size() - 1).rows().add(new ParsedCase.AssignRow(
                        parseLong(ln, f[0], 0), f[1], parseInt(ln, f[2], 2),
                        parseDouble(ln, f[3], 3, "score")));
            }
        }
        List<ParsedCase.DecisionRow> decisions = new ArrayList<>();
        for (Line ln : readLines(dir, "decisions.dec")) {

            String[] f = split(ln, 8);
            Map<String, Object> payload = parseJson(f[7],
                    new TypeReference<Map<String, Object>>() {
                    }, "decisions.dec@" + ln.no);
            decisions.add(new ParsedCase.DecisionRow(f[0], parseLong(ln, f[1], 1), f[2], f[3],
                    f[4], f[5], f[6], payload.getOrDefault("adopted", "ADOPTED").toString(), payload));
        }
        return new ParsedCase(manifest, waveforms, sites, thresholds, spikes, features,
                clusterFiles, decisions, rules, scenarios);
    }

    private String readRequired(Path dir, String name) {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) {
            throw new ParseException("missing required file " + name, List.of(
                    org.neurospike.domain.Diagnostic.of
                            (org.neurospike.domain.Diagnostic.CORRUPT_FORMAT, name, 0, 0,
                                    "required file missing", null, Map.of())));
        }
        return readString(p, name);
    }

    private String readString(Path p, String name) {
        try {
            long size = Files.size(p);
            if (size > maxBytesPerFile) {
                throw new BudgetException(new Diagnostic(
                        Diagnostic.BUDGET_EXHAUSTED, name, 0, 0,
                        "file byte budget exhausted: " + size + " > " + maxBytesPerFile,
                        String.valueOf(size), Map.of("limit", maxBytesPerFile)));
            }
            return Files.readString(p);
        } catch (IOException e) {
            throw new ParseException("cannot read " + name, List.of(
                    org.neurospike.domain.Diagnostic.of
                            (org.neurospike.domain.Diagnostic.CORRUPT_FORMAT, name, 0, 0,
                                    e.getMessage(), null, Map.of())));
        }
    }

    private List<Line> readLines(Path dir, String name) {
        String content = readRequired(dir, name);
        List<Line> out = new ArrayList<>();
        String[] raw = content.split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String t = raw[i].trim();
            if (t.isEmpty() || t.startsWith("#") && !t.startsWith("## ")) {
                continue;
            }
            if (out.size() >= maxRowsPerFile) {
                throw new BudgetException(new Diagnostic(
                        Diagnostic.BUDGET_EXHAUSTED, name, i + 1, 0,
                        "row budget exhausted: > " + maxRowsPerFile, t,
                        Map.of("limit", maxRowsPerFile)));
            }
            out.add(new Line(i + 1, t));
        }
        return out;
    }

    private <T> T parseJson(String text, TypeReference<T> type, String where) {
        try {
            return Json.MAPPER.readValue(text, type);
        } catch (Exception e) {
            throw new ParseException("malformed json in " + where, List.of(
                    org.neurospike.domain.Diagnostic.of
                            (org.neurospike.domain.Diagnostic.CORRUPT_FORMAT, where, 0, 0,
                                    e.getMessage(), text, Map.of())));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String[] split(Line ln, int expected) {
        String[] f = ln.text.split("\\|", -1);
        if (f.length < expected) {
            throw new ParseException("wrong column count", List.of(DiagnosticBuilder.corrupt(
                    "line", ln.no, f.length, "expected >= " + expected + " fields, got " + f.length,
                    ln.text)));
        }
        for (int i = 0; i < f.length; i++) {
            f[i] = f[i].trim();
        }
        return f;
    }

    private static List<Integer> parseChannelList(Line ln, String token) {
        if (token.isBlank()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String part : token.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                throw new ParseException("bad channel list", List.of(DiagnosticBuilder.corrupt(
                        "spikes.spk", ln.no, 0, "bad channel token " + part, token)));
            }
        }
        return out;
    }

    private static int parseInt(Line ln, String token, int col) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new ParseException("bad integer", List.of(DiagnosticBuilder.corrupt(
                    "line", ln.no, col + 1, "expected integer but got '" + token + "'", token)));
        }
    }

    private static long parseLong(Line ln, String token, int col) {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new ParseException("bad long", List.of(DiagnosticBuilder.corrupt(
                    "line", ln.no, col + 1, "expected integer but got '" + token + "'", token)));
        }
    }

    private static double parseDouble(Line ln, String token, int col, String what) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new ParseException("bad number", List.of(DiagnosticBuilder.corrupt(
                    "line", ln.no, col + 1, "expected " + what + " but got '" + token + "'", token)));
        }
    }

    private record Line(int no, String text) {
    }
}
