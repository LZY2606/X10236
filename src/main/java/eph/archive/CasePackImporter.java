package eph.archive;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports an offline {@link CasePack} into the layered stores.
 *
 * <p>Failure contract:
 * <ul>
 *   <li>malformed JSON / wrong structural shape -> {@link Outcome#FORMAT_CORRUPT},</li>
 *   <li>parseable JSON with impossible domain meaning (duplicate ids, dangling references,
 *       cross-channel concurrent-spike inconsistency) -> {@link Outcome#SEMANTIC_UNRESOLVABLE},</li>
 *   <li>row/result budget exceeded -> {@link Outcome#BUDGET_EXHAUSTED},</li>
 *   <li>individual unconvertible physical values -> {@link Outcome#LOCAL_FAILURE}: the raw value
 *       is stored in place together with a positioned diagnostic.</li>
 * </ul>
 * No null/default is ever substituted for a broken domain value.
 */
public final class CasePackImporter {

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CasePackImporter(Database db) {
        this.db = db;
    }

    public static final class Budget {
        public int maxSpikes = 100_000;
        public int maxSites = 10_000;
    }

    public record ImportReport(String projectId, Outcome outcome, List<Diagnostic> diagnostics,
                               int stepsReplayed, boolean recoveredHalfWrite) {
    }

    /** Hook used by tests to kill the process of a write mid-flight. */
    public interface CrashHook {
        void beforeStepInsert(int stepSeq);
    }

    public ImportReport importPack(String json, String projectId, Budget budget) {
        return importPack(json, projectId, budget, null);
    }

    public ImportReport importPack(String json, String projectId, Budget budget, CrashHook crashHook) {
        List<Diagnostic> parseDiagnostics = new ArrayList<>();
        CasePack pack = parse(json, parseDiagnostics);
        Outcome parseOutcome = Outcome.from(parseDiagnostics);
        if (parseOutcome != Outcome.OK && parseOutcome != Outcome.LOCAL_FAILURE) {
            return new ImportReport(projectId, parseOutcome, List.copyOf(parseDiagnostics), 0, false);
        }
        if (pack == null) {
            return new ImportReport(projectId, Outcome.FORMAT_CORRUPT,
                    List.of(Diagnostic.format("EMPTY_PACK", "$", "no case pack content")), 0, false);
        }

        List<Diagnostic> semantic = new ArrayList<>();
        validate(pack, budget == null ? new Budget() : budget, semantic);
        Outcome semOutcome = Outcome.from(semantic);
        if (semOutcome == Outcome.FORMAT_CORRUPT || semOutcome == Outcome.SEMANTIC_UNRESOLVABLE
                || semOutcome == Outcome.BUDGET_EXHAUSTED) {
            List<Diagnostic> all = new ArrayList<>(parseDiagnostics);
            all.addAll(semantic);
            return new ImportReport(projectId, semOutcome, List.copyOf(all), 0, false);
        }

        boolean recovered = db.projectExists(projectId)
                && !db.rolledBackTransactions(projectId).isEmpty();

        db.inTransaction(c -> {
            String txId = db.beginLedger(c, projectId);
            try {
                writeMaterial(c, projectId, pack, semantic);
                ReplayEngine engine = new ReplayEngine(db, c);
                engine.replay(pack, projectId, semantic, crashHook);
                db.commitLedger(c, txId);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        List<Diagnostic> all = new ArrayList<>(parseDiagnostics);
        all.addAll(semantic);
        return new ImportReport(projectId, Outcome.from(all), List.copyOf(all),
                pack.steps().size(), recovered);
    }

    CasePack parse(String json, List<Diagnostic> diagnostics) {
        if (json == null || json.isBlank()) {
            diagnostics.add(Diagnostic.format("EMPTY_PACK", "$", "case pack is empty"));
            return null;
        }
        try {
            CasePack pack = mapper.readValue(json, CasePack.class);
            if (pack.packId() == null || pack.recording() == null || pack.probe() == null
                    || pack.spikes() == null || pack.steps() == null) {
                diagnostics.add(Diagnostic.format("MISSING_SECTION", "$",
                        "case pack misses one of packId/recording/probe/spikes/steps"));
                return null;
            }
            return pack;
        } catch (Exception e) {
            diagnostics.add(Diagnostic.format("MALFORMED_JSON", "$",
                    "case pack is not valid JSON: " + e.getClass().getSimpleName()));
            return null;
        }
    }

    private void validate(CasePack pack, Budget budget, List<Diagnostic> d) {
        if (pack.spikes().size() > budget.maxSpikes) {
            d.add(Diagnostic.budget("TOO_MANY_SPIKES", "$.spikes",
                    pack.spikes().size() + " spikes exceed budget " + budget.maxSpikes));
        }
        if (pack.probe().sites().size() > budget.maxSites) {
            d.add(Diagnostic.budget("TOO_MANY_SITES", "$.probe.sites",
                    pack.probe().sites().size() + " sites exceed budget " + budget.maxSites));
        }

        Map<String, CasePack.SpikeSpec> byId = new LinkedHashMap<>();
        for (CasePack.SpikeSpec s : pack.spikes()) {
            if (byId.putIfAbsent(s.id(), s) != null) {
                d.add(Diagnostic.semantic("DUPLICATE_SPIKE_ID", "$.spikes",
                        "duplicate spike id " + s.id()));
            }
        }
        for (CasePack.StepSpec step : pack.steps()) {
            for (String changed : step.changedSpikeIds() == null ? List.<String>of()
                    : step.changedSpikeIds()) {
                if (!byId.containsKey(changed)) {
                    d.add(Diagnostic.semantic("DANGLING_SOURCE_REF",
                            "$.steps[" + step.seq() + "].changedSpikeIds",
                            "unknown spike id " + changed));
                }
            }
            for (CasePack.DecisionSpec dec : step.decisions() == null ? List.<CasePack.DecisionSpec>of()
                    : step.decisions()) {
                if (dec.spikeId() != null && !byId.containsKey(dec.spikeId())) {
                    d.add(Diagnostic.semantic("DANGLING_DECISION_SPIKE",
                            "$.steps[" + step.seq() + "].decisions",
                            "decision references unknown spike " + dec.spikeId()));
                }
                if (dec.action() == null || dec.action().isBlank()) {
                    d.add(Diagnostic.semantic("MISSING_ACTION",
                            "$.steps[" + step.seq() + "].decisions",
                            "adjudication needs an action"));
                }
            }
        }

        // Concurrent-spike pairs must be mutually referenced on two distinct channels.
        for (CasePack.SpikeSpec s : pack.spikes()) {
            if (s.pairedChannel() != null) {
                CasePack.SpikeSpec partner = findAt(pack.spikes(), s.pairedChannel(), s.time());
                if (partner == null) {
                    d.add(Diagnostic.semantic("ORPHAN_PROPAGATION", "$.spikes." + s.id(),
                            "paired channel " + s.pairedChannel() + " has no event at " + s.time()));
                } else if (partner.channel() == s.channel()) {
                    d.add(Diagnostic.semantic("SAME_CHANNEL_PROPAGATION", "$.spikes." + s.id(),
                            "multi-channel propagation must reference a different channel"));
                }
            }
        }

        // Label batches must only reference existing spikes and known feature versions.
        java.util.Set<String> featureIds = new java.util.HashSet<>();
        for (CasePack.FeatureVersionSpec fv : pack.featureVersions()) {
            featureIds.add(fv.id());
        }
        for (CasePack.LabelBatchSpec b : pack.labelBatches()) {
            if (!featureIds.contains(b.featureVersionId())) {
                d.add(Diagnostic.semantic("DANGLING_FEATURE_VERSION", "$.labelBatches." + b.id(),
                        "unknown feature version " + b.featureVersionId()));
            }
            for (CasePack.AssignmentSpec a : b.assignments()) {
                if (!byId.containsKey(a.spikeId())) {
                    d.add(Diagnostic.semantic("DANGLING_LABEL_SPIKE",
                            "$.labelBatches." + b.id(), "unknown spike " + a.spikeId()));
                }
                if (a.clusterNumber() == null || a.unitId() == null) {
                    d.add(Diagnostic.semantic("INCOMPLETE_ASSIGNMENT",
                            "$.labelBatches." + b.id() + "." + a.spikeId(),
                            "assignment requires cluster number and stable unit id"));
                }
            }
        }

        long steps = pack.steps().stream().filter(x -> x.seq() == 0).count();
        if (steps != 1) {
            d.add(Diagnostic.semantic("STEP_ZERO_UNIQUE", "$.steps",
                    "exactly one INITIAL step (seq 0) is required"));
        }
    }

    private static CasePack.SpikeSpec findAt(List<CasePack.SpikeSpec> spikes, int channel, String time) {
        for (CasePack.SpikeSpec s : spikes) {
            if (s.channel() == channel && time.equals(s.time())) {
                return s;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void writeMaterial(Connection c, String projectId, CasePack pack,
                               List<Diagnostic> diagnostics) throws SQLException {
        Sql.upsert(c,
                "INSERT INTO project(project_id,title,case_pack_id,created_at_utc) VALUES(?,?,?,?)",
                projectId, pack.title(), pack.packId(), "2026-09-20T08:00:00Z");

        // Recording — raw representation preserved, canonical values derived if possible.
        List<Diagnostic> recDiag = new ArrayList<>();
        Quantity sr = Quantity.parse(pack.recording().sampleRate(), "$.recording.sampleRate", recDiag);
        Quantity dur = Quantity.parse(pack.recording().duration(), "$.recording.duration", recDiag);
        persistDiagnostics(c, projectId, -1, recDiag);
        diagnostics.addAll(recDiag);
        Sql.upsert(c, """
                INSERT INTO recording(project_id,recording_id,start_wall_clock_utc,
                    sample_rate_raw,duration_raw,sample_rate_hz,duration_s)
                VALUES(?,?,?,?,?,?,?)""",
                projectId, pack.recording().id(), pack.recording().startWallClockUtc(),
                sr.raw(), dur.raw(),
                sr.usable() && "Hz".equals(sr.canonicalUnit()) ? sr.canonicalValue() : null,
                dur.usable() && "s".equals(dur.canonicalUnit()) ? dur.canonicalValue() : null);

        // Probe geometry with explicit coordinate frame; bad coordinates stay raw.
        Sql.upsert(c,
                "INSERT INTO probe(project_id,probe_id,type,coordinate_frame) VALUES(?,?,?,?)",
                projectId, pack.probe().id(), pack.probe().type(), pack.probe().coordinateFrame());
        for (CasePack.SiteSpec site : pack.probe().sites()) {
            List<Diagnostic> siteDiag = new ArrayList<>();
            Coordinate coord = Coordinate.parse(site.x(), site.y(), site.z(),
                    pack.probe().coordinateFrame(), "$.probe.sites." + site.id(), siteDiag);
            persistDiagnostics(c, projectId, -1, siteDiag);
            diagnostics.addAll(siteDiag);
            Sql.upsert(c, """
                    INSERT INTO probe_site(project_id,site_id,channel,x_raw,y_raw,z_raw,
                        x_m,y_m,z_m,unit_raw) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    projectId, site.id(), site.channel(), site.x(), site.y(), site.z(),
                    coord.canonicalX(), coord.canonicalY(), coord.canonicalZ(), site.x());
        }

        // Waveform references: references, never the raw recording itself.
        for (CasePack.WaveformSourceSpec w : pack.waveformSources()) {
            Sql.upsert(c, """
                    INSERT INTO waveform_source(project_id,waveform_id,kind,uri,encoding,
                        amplitude_unit,time_unit,samples_per_snippet,sample_rate_raw)
                    VALUES(?,?,?,?,?,?,?,?,?)""",
                    projectId, w.id(), w.kind(), w.uri(), w.encoding(), w.amplitudeUnit(),
                    w.timeUnit(), w.samplesPerSnippet(), w.sampleRate());
        }

        // Detection runs + thresholds (unknown-unit threshold retained raw).
        java.util.Set<String> runs = new java.util.LinkedHashSet<>();
        for (CasePack.ThresholdSpec t : pack.thresholds()) {
            if (runs.add(t.detectionRunId())) {
                Sql.upsert(c,
                        "INSERT INTO detection_run(project_id,run_id,parameter_version) VALUES(?,?,?)",
                        projectId, t.detectionRunId(), t.parameterVersion());
            }
            List<Diagnostic> thDiag = new ArrayList<>();
            // Sigma-relative thresholds are domain-legal with no absolute canonical value;
            // only genuinely unconvertible literals (e.g. "-138 µV??") become diagnostics.
            Double canonical = parseThresholdCanonical(t.value(),
                    "$.thresholds[" + t.channel() + "].value", thDiag);
            persistDiagnostics(c, projectId, -1, thDiag);
            diagnostics.addAll(thDiag);
            Sql.upsert(c, """
                    INSERT INTO detection_threshold(project_id,run_id,channel,value_raw,
                        canonical_value,basis) VALUES(?,?,?,?,?,?)""",
                    projectId, t.detectionRunId(), t.channel(), t.value(),
                    canonical, t.basis() == null ? "" : t.basis());
        }

        // Detected events: time/amplitude raw kept; snippets embedded; broken amplitude stays.
        for (CasePack.SpikeSpec s : pack.spikes()) {
            List<Diagnostic> spDiag = new ArrayList<>();
            Quantity tq = Quantity.parse(s.time(), "$.spikes." + s.id() + ".time", spDiag);
            Quantity aq = Quantity.parse(s.amplitude(),
                    "$.spikes." + s.id() + ".amplitude", spDiag);
            persistDiagnostics(c, projectId, -1, spDiag);
            diagnostics.addAll(spDiag);
            Sql.upsert(c, """
                    INSERT INTO spike(project_id,spike_id,channel,time_raw,time_s,
                        amplitude_raw_text,amplitude_v,snippet_json,waveform_id,paired_channel,note)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    projectId, s.id(), s.channel(), s.time(),
                    tq.usable() && "s".equals(tq.canonicalUnit()) ? tq.canonicalValue() : null,
                    s.amplitude(),
                    aq.usable() && "V".equals(aq.canonicalUnit()) ? aq.canonicalValue() : null,
                    Json.snippets(s.snippet()), s.sourceWaveformId(), s.pairedChannel(), s.note());
        }

        // Feature versions.
        for (CasePack.FeatureVersionSpec fv : pack.featureVersions()) {
            Sql.upsert(c, """
                    INSERT INTO feature_version(project_id,feature_version_id,name,description,
                        parameters_json) VALUES(?,?,?,?,?)""",
                    projectId, fv.id(), fv.name(), fv.description(),
                    Json.write(fv.parameters() == null ? Map.of() : fv.parameters()));
        }

        // Label batches/assignments (cluster number is disposable; unit_id is stable).
        for (CasePack.LabelBatchSpec b : pack.labelBatches()) {
            Sql.upsert(c, """
                    INSERT INTO label_batch(project_id,batch_id,feature_version_id,parameter_version,
                        generated_at) VALUES(?,?,?,?,?)""",
                    projectId, b.id(), b.featureVersionId(), b.parameterVersion(), b.generatedAt());
            for (CasePack.AssignmentSpec a : b.assignments()) {
                Sql.upsert(c, """
                        INSERT INTO label_assignment(project_id,batch_id,spike_id,cluster_number,
                            unit_id,note) VALUES(?,?,?,?,?,?)""",
                        projectId, b.id(), a.spikeId(), a.clusterNumber(), a.unitId(), a.note());
            }
        }
    }

    static Double parseThresholdCanonical(String raw, String location, List<Diagnostic> d) {
        if (raw != null && raw.contains("σ")) {
            return null; // relative threshold; legal, no canonical absolute value
        }
        Quantity q = Quantity.parse(raw, location, d);
        return q.usable() && "V".equals(q.canonicalUnit()) ? q.canonicalValue() : null;
    }

    static void persistDiagnostics(Connection c, String projectId, int stepSeq,
                                   List<Diagnostic> diagnostics) throws SQLException {
        for (Diagnostic d : diagnostics) {
            String id = Ids.content("diag", projectId, String.valueOf(stepSeq),
                    d.severity(), d.code(), d.location(), d.message());
            Sql.upsert(c, """
                    INSERT INTO diagnostic(project_id,diagnostic_id,step_seq,severity,code,
                        location,message,context_json) VALUES(?,?,?,?,?,?,?,?)""",
                    projectId, id, stepSeq, d.severity(), d.code(), d.location(), d.message(),
                    Json.write(d.context()));
        }
    }
}
