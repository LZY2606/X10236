package gsb.spikesort.domain;

import com.fasterxml.jackson.databind.JsonNode;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Imports multi-channel detection events, waveform snippets and recording
 *  times. Raw representations are preserved; normalization happens only on
 *  the derived layer. Values that cannot be converted stay in place with a
 *  positioned diagnostic — never replaced by nulls or defaults.
 *
 *  Outcomes are three distinct results: BUDGET_EXHAUSTED, FORMAT_CORRUPT and
 *  SEMANTIC_UNSOLVABLE, plus OK. */
public final class IngestService {
    public static final int DEFAULT_SPIKE_BUDGET = 100_000;

    private final Store store;
    private final int spikeBudget;

    public IngestService(Store store) { this(store, DEFAULT_SPIKE_BUDGET); }

    public IngestService(Store store, int spikeBudget) {
        this.store = store;
        this.spikeBudget = spikeBudget;
    }

    private static Diagnostic diag(String stage, String location, Severity sev,
                                   String code, String message, String raw) {
        return new Diagnostic("diag-" + UUID.nameUUIDFromBytes(
                (stage + location + code + message).getBytes()).toString().substring(0, 13),
                stage, location, sev, code, message, raw);
    }

    /** Parse + import a materials document. Never throws for bad input:
     *  failures come back as a typed IngestResult. */
    public IngestResult importMaterials(String jsonText) {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(jsonText);
        } catch (Exception e) {
            return new IngestResult(IngestStatus.FORMAT_CORRUPT, List.of(
                    diag("ingest", "$", Severity.ERROR, "FORMAT_CORRUPT",
                            "document is not valid JSON: " + e.getMessage(), null)), 0);
        }
        return importMaterials(root);
    }

    public IngestResult importMaterials(JsonNode root) {
        List<Diagnostic> diags = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return new IngestResult(IngestStatus.FORMAT_CORRUPT, List.of(
                    diag("ingest", "$", Severity.ERROR, "FORMAT_CORRUPT",
                            "materials document must be a JSON object", null)), 0);
        }

        // --- semantic pre-checks: missing required sections are unsolvable ---
        List<String> required = List.of("probeGeometry", "detectionParams", "featureSet", "spikes");
        for (String field : required) {
            if (!root.has(field) || root.get(field).isNull()) {
                diags.add(diag("ingest", "$." + field, Severity.ERROR, "MISSING_SECTION",
                        "required section '" + field + "' is missing; cannot establish provenance chain", null));
            }
        }
        JsonNode dp = root.get("detectionParams");
        if (dp != null && dp.isObject()) {
            double ws = dp.path("windowStartMs").asDouble(Double.NaN);
            double we = dp.path("windowEndMs").asDouble(Double.NaN);
            if (!Double.isNaN(ws) && !Double.isNaN(we) && !(we > ws)) {
                diags.add(diag("ingest", "$.detectionParams.windowEndMs", Severity.ERROR,
                        "WINDOW_INVERTED", "detection window end must be greater than start", dp.toString()));
            }
        }
        if (diags.stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
            return new IngestResult(IngestStatus.SEMANTIC_UNSOLVABLE, diags, 0);
        }

        JsonNode spikes = root.get("spikes");
        if (spikes.isArray() && spikes.size() > spikeBudget) {
            diags.add(diag("ingest", "$.spikes", Severity.ERROR, "BUDGET_EXHAUSTED",
                    "spike count " + spikes.size() + " exceeds budget " + spikeBudget, null));
            return new IngestResult(IngestStatus.BUDGET_EXHAUSTED, diags, 0);
        }

        int imported = 0;
        store.begin();
        try {
            // --- probe geometry (immutable material, µm probe frame) ---
            JsonNode pg = root.get("probeGeometry");
            List<ProbeGeometry.Site> sites = new ArrayList<>();
            int idx = 0;
            for (JsonNode s : pg.path("sites")) {
                String loc = "$.probeGeometry.sites[" + idx + "]";
                if (!s.has("channel") || !s.has("xUm") || !s.has("yUm")) {
                    diags.add(diag("ingest", loc, Severity.ERROR, "INCOMPLETE_SITE",
                            "probe site requires channel, xUm, yUm", s.toString()));
                } else {
                    sites.add(new ProbeGeometry.Site(s.get("channel").asInt(),
                            s.get("xUm").asDouble(), s.get("yUm").asDouble()));
                }
                idx++;
            }
            if (diags.stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
                store.rollbackQuietly();
                return new IngestResult(IngestStatus.SEMANTIC_UNSOLVABLE, diags, 0);
            }
            int pgVersion = pg.path("version").asInt(1);
            String pgDigest = Json.digestOf(Json.canonical(pg));
            store.insertProbeGeometry(new ProbeGeometry("probe-v" + pgVersion, pgVersion,
                    pg.path("frame").asText("probe_um"), sites, pgDigest));

            // --- detection params (immutable material) ---
            int dpVersion = dp.path("version").asInt(1);
            String dpDigest = Json.digestOf(Json.canonical(dp));
            store.insertDetectionParams(new DetectionParams("detparam-v" + dpVersion, dpVersion,
                    dp.path("thresholdUv").asDouble(), dp.path("refractoryMs").asDouble(),
                    dp.path("windowStartMs").asDouble(), dp.path("windowEndMs").asDouble(), dpDigest));

            // --- feature set version ---
            JsonNode fs = root.get("featureSet");
            int fsVersion = fs.path("version").asInt(1);
            store.insertFeatureSet(new FeatureSet("feat-v" + fsVersion, fsVersion,
                    fs.path("method").asText("pca-2d"),
                    fs.path("inputDigest").asText(""), Json.digestOf(Json.canonical(fs))));

            // --- waveform refs ---
            int widx = 0;
            for (JsonNode w : root.path("waveformRefs")) {
                String loc = "$.waveformRefs[" + widx + "]";
                if (!w.has("id") || !w.has("channel") || !w.has("tStartMs") || !w.has("tEndMs")) {
                    diags.add(diag("ingest", loc, Severity.ERROR, "INCOMPLETE_WAVEFORM_REF",
                            "waveform ref requires id, channel, tStartMs, tEndMs", w.toString()));
                } else if (w.get("tEndMs").asDouble() <= w.get("tStartMs").asDouble()) {
                    diags.add(diag("ingest", loc + ".tEndMs", Severity.ERROR, "WINDOW_INVERTED",
                            "waveform snippet end must be after start", w.toString()));
                } else {
                    store.insertWaveformRef(new WaveformRef(w.get("id").asText(),
                            w.get("channel").asInt(), w.get("tStartMs").asDouble(),
                            w.get("tEndMs").asDouble(), w.path("samplingRateHz").asDouble(30000.0),
                            w.path("amplitudeUnit").asText("uV"),
                            w.path("coordFrame").asText("probe_um"),
                            w.path("sourceUri").asText("local://unknown"),
                            w.toString(), Json.digestOf(Json.canonical(w))));
                    imported++;
                }
                widx++;
            }

            // --- units + cluster labels (labels reference stable unit ids) ---
            String importEventId = root.path("importEventId").asText("evt-import");
            for (JsonNode u : root.path("units")) {
                store.insertUnit(new Unit(u.get("id").asText(), importEventId,
                        u.path("status").asText("active")));
            }
            for (JsonNode l : root.path("clusterLabels")) {
                store.insertClusterLabel(new ClusterLabel(
                        l.path("id").asText("label-" + UUID.randomUUID()),
                        l.get("featureSetId").asText(), l.get("clusterIndex").asInt(),
                        l.get("unitId").asText(), importEventId));
            }

            // --- spikes: raw amplitude preserved; derived layer normalizes ---
            int sidx = 0;
            for (JsonNode s : spikes) {
                String loc = "$.spikes[" + sidx + "]";
                if (!s.has("id") || !s.has("timeMs") || !s.has("channel")) {
                    diags.add(diag("ingest", loc, Severity.ERROR, "INCOMPLETE_SPIKE",
                            "spike requires id, timeMs, channel", s.toString()));
                    sidx++;
                    continue;
                }
                String ampRaw = s.has("amplitude") ? s.get("amplitude").asText() : null;
                Double ampUv = null;
                if (ampRaw == null) {
                    diags.add(diag("ingest", loc + ".amplitude", Severity.ERROR, "MISSING_AMPLITUDE",
                            "spike amplitude is required (raw representation kept in place)", null));
                } else {
                    ampUv = normalizeAmplitudeToUv(ampRaw);
                    if (ampUv == null) {
                        diags.add(diag("ingest", loc + ".amplitude", Severity.WARNING,
                                "UNIT_UNCONVERTIBLE",
                                "amplitude '" + ampRaw + "' cannot be converted to µV; raw value kept in place", ampRaw));
                    }
                }
                List<String> candidates = new ArrayList<>();
                for (JsonNode c : s.path("candidateUnitIds")) candidates.add(c.asText());
                store.insertSpike(new SpikeEvent(s.get("id").asText(), s.get("timeMs").asDouble(),
                        s.get("channel").asInt(), ampRaw == null ? "" : ampRaw, ampUv,
                        s.hasNonNull("unitId") ? s.get("unitId").asText() : null,
                        s.hasNonNull("waveformRefId") ? s.get("waveformRefId").asText() : null,
                        candidates));
                imported++;
                sidx++;
            }

            if (diags.stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
                store.rollbackQuietly();
                return new IngestResult(IngestStatus.SEMANTIC_UNSOLVABLE, diags, 0);
            }
            for (Diagnostic d : diags) store.insertDiagnostic(d);
            store.commit();
            return new IngestResult(IngestStatus.OK, diags, imported);
        } catch (Exception e) {
            store.rollbackQuietly();
            diags.add(diag("ingest", "$", Severity.ERROR, "SEMANTIC_UNSOLVABLE",
                    "import aborted, transaction rolled back: " + e.getMessage(), null));
            return new IngestResult(IngestStatus.SEMANTIC_UNSOLVABLE, diags, 0);
        }
    }

    private static final Pattern AMP = Pattern.compile(
            "^\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*(µV|uV|mV|V)\\s*$");

    /** Derived-layer normalization. Returns null when unconvertible. */
    public static Double normalizeAmplitudeToUv(String raw) {
        Matcher m = AMP.matcher(raw);
        if (!m.matches()) return null;
        double v = Double.parseDouble(m.group(1));
        return switch (m.group(2)) {
            case "µV", "uV" -> v;
            case "mV" -> v * 1000.0;
            case "V" -> v * 1_000_000.0;
            default -> null;
        };
    }
}
