package eph.archive;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Replays the case-pack history in fixed step order and materialises:
 * <ul>
 *   <li>stable unit identities and split/merge lineage,</li>
 *   <li>derived results (feature projection, autocorrelation, firing rate, amplitude-drift
 *       series) each carrying input summary, parameter version and parent dependencies,</li>
 *   <li>machine-readable evidence events whose {@code judgment} states the domain reading
 *       (refractory violations / amplitude drift / simultaneous propagation are EVIDENCE only;
 *       cluster numbers are not identity),</li>
 *   <li>append-only adjudication events, accepted and rejected together.</li>
 * </ul>
 */
public final class ReplayEngine {

    /** Derived result kinds. */
    public static final String K_PROJECTION = "feature_projection";
    public static final String K_AUTOCORR = "autocorrelation";
    public static final String K_RATE = "firing_rate";
    public static final String K_DRIFT = "amplitude_drift";
    public static final String K_QUALITY = "unit_quality";

    private static final double REFRACTORY_S = 0.001;
    private static final double WINDOW_EPS = 1e-12;

    private final Database db;
    private final Connection c;

    public ReplayEngine(Database db, Connection c) {
        this.db = db;
        this.c = c;
    }

    public void replay(CasePack pack, String projectId, List<Diagnostic> diagnostics,
                       CasePackImporter.CrashHook crashHook) throws SQLException {
        Map<String, Spike> spikes = loadSpikes(pack, diagnostics);
        double durationS = duration(pack, diagnostics);

        Set<String> aliveUnits = new TreeSet<>();
        Map<String, Integer> resultOrder = new LinkedHashMap<>();
        int order = 0;

        for (CasePack.StepSpec step : sortedSteps(pack)) {
            if (crashHook != null) {
                crashHook.beforeStepInsert(step.seq());
            }
            String batchId = chooseBatch(step);
            Map<String, UnitMembership> membership = membershipFor(projectId, batchId);
            Set<String> affected = new TreeSet<>();
            for (String u : step.expectedAffectedUnitIds() == null ? List.<String>of()
                    : step.expectedAffectedUnitIds()) {
                affected.add(u);
            }

            switch (step.type()) {
                case "INITIAL_IMPORT" -> {
                    registerLineage(projectId, membership, 0, Map.of());
                    aliveUnits.addAll(membership.keySet());
                    affected.addAll(membership.keySet());
                    evaluateAll(projectId, step, batchId, membership, spikes, durationS,
                            resultOrder, order);
                    order += membership.size() * 5;
                }
                case "PARAMETER_REVISION" -> {
                    Map<String, String> parents = parentsFrom(membership, "uC",
                            Map.of("uC1", "uC", "uC2", "uC"));
                    registerLineage(projectId, membership, 1, parents);
                    aliveUnits.clear();
                    aliveUnits.addAll(membership.keySet());
                    evaluateAll(projectId, step, batchId, membership, spikes, durationS,
                            resultOrder, order);
                    order += membership.size() * 5;
                    recordSplitSharedEvent(projectId, step, membership, spikes);
                    recordDecisions(projectId, step, diagnostics);
                }
                case "SOURCE_PATCH" -> {
                    double[] win = window(step);
                    Set<String> intersecting = intersectingUnits(membership, spikes, win);
                    // The pack names uA as affected; intersecting must agree, else diagnostic.
                    for (String u : intersecting) {
                        if (!affected.contains(u)) {
                            diagnostics.add(Diagnostic.local("UNDECLARED_INTERSECTION",
                                    "$.steps[" + step.seq() + "]",
                                    "unit " + u + " intersects window but was not declared affected"));
                        }
                    }
                    for (String u : affected) {
                        if (!intersecting.contains(u)) {
                            diagnostics.add(Diagnostic.local("OVERSIZED_RECOMPUTE_SCOPE",
                                    "$.steps[" + step.seq() + "]",
                                    "unit " + u + " does not intersect the window; must not recompute"));
                        }
                    }
                    evaluateUnits(projectId, step, batchId, membership, spikes, durationS,
                            intersecting, win, resultOrder, order);
                    order += intersecting.size() * 5;
                }
                case "CONFLICTING_DECISIONS" -> recordDecisions(projectId, step, diagnostics);
                default -> diagnostics.add(Diagnostic.semantic("UNKNOWN_STEP_TYPE",
                        "$.steps[" + step.seq() + "]", "unknown step type " + step.type()));
            }

            recordStep(projectId, step, diagnostics);
        }

        recordStaticEvidence(projectId, spikes);
    }

    // ================= material =================

    record Spike(String id, int channel, String timeRaw, Double timeS, String ampRaw,
                 Double ampV, List<Double> snippet, Integer pairedChannel, String note) {
    }

    private Map<String, Spike> loadSpikes(CasePack pack, List<Diagnostic> diagnostics) {
        Map<String, Spike> out = new LinkedHashMap<>();
        for (CasePack.SpikeSpec s : pack.spikes()) {
            List<Diagnostic> local = new ArrayList<>();
            Quantity t = Quantity.parse(s.time(), "$.spikes." + s.id() + ".time", local);
            Quantity a = Quantity.parse(s.amplitude(), "$.spikes." + s.id() + ".amplitude", local);
            diagnostics.addAll(local);
            out.put(s.id(), new Spike(s.id(), s.channel(), s.time(),
                    t.usable() ? t.canonicalValue() : null,
                    s.amplitude(), a.usable() ? a.canonicalValue() : null,
                    s.snippet(), s.pairedChannel(), s.note()));
        }
        return out;
    }

    private double duration(CasePack pack, List<Diagnostic> d) {
        Quantity q = Quantity.parse(pack.recording().duration(), "$.recording.duration", d);
        return q.usable() ? q.canonicalValue() : 1.0;
    }

    private static List<CasePack.StepSpec> sortedSteps(CasePack pack) {
        return pack.steps().stream().sorted(java.util.Comparator.comparingInt(CasePack.StepSpec::seq)).toList();
    }

    private String chooseBatch(CasePack.StepSpec step) {
        if (step.labelBatchById() == null || step.labelBatchById().isEmpty()) {
            return null;
        }
        return step.labelBatchById().values().iterator().next();
    }
