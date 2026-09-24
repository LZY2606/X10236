package neurospike;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure derivation entry point. Business rules live here (server-side), never in
 * the browser: membership/lineage, evidence, features, rate and autocorrelation
 * are all derived from a replay {@link State}, deterministically.
 */
public final class Engine {

    private Engine() {
    }

    public static Evaluation evaluate(State state, long seq, WindowSpec window) {
        long target = Math.min(seq, state.events().size());
        MembershipEngine.MembershipResult membership = MembershipEngine.at(state, target);
        FeatureEngine.ProjectionResult proj = FeatureEngine.project(state, membership, target);
        EvidenceEngine.EvidenceResult evidence = EvidenceEngine.evaluate(
                state, membership, target, window);
        List<RateBin> rates = StatsEngine.rate(state, membership, window, target);
        List<AutocorrBin> autocorr = StatsEngine.autocorr(state, membership, window, target);

        Event atEvent = state.eventsAtOrBefore(target)
                .get((int) Math.min(target, state.eventsAtOrBefore(target).size()) - 1);
        String inputDigest = digestAt(state, target);
        String paramVersion = state.specAtOrBefore(target).versionId();

        List<String> ordering = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Finding f : evidence.findings()) {
            if (seen.add(f.findingId())) {
                ordering.add(f.findingId());
            }
        }
        java.util.Collections.sort(ordering);

        List<Diagnostic> diagnostics = new ArrayList<>(membership.diagnostics());
        diagnostics.addAll(proj.diagnostics());
        diagnostics.addAll(evidence.diagnostics());
        diagnostics.sort((a, b) -> a.pointer().compareTo(b.pointer()));

        return new Evaluation(atEvent.eventId(), inputDigest, paramVersion,
                membership.clusters(), evidence.findings(), membership.edges(),
                proj.features(), proj.points(), rates, autocorr, ordering, diagnostics);
    }

    public static Evaluation head(State state, WindowSpec window) {
        return evaluate(state, state.events().size(), window);
    }

    /** Content digest of every material layer active at seq. */
    public static String digestAt(State state, long seq) {
        List<String> parts = new ArrayList<>();
        state.detectionAtOrBefore(seq).values().stream()
                .map(MaterialRev::contentHash).sorted().forEach(parts::add);
        state.waveformAtOrBefore(seq).values().stream()
                .map(MaterialRev::contentHash).sorted().forEach(parts::add);
        parts.add(state.specAtOrBefore(seq).contentHash());
        parts.add(state.labelsAtOrBefore(seq).contentHash());
        parts.add("thresholds:" + Canonical.shortHash(Canonical.of(state.thresholds(),
                Json.MAPPER)));
        parts.add("probe:" + Canonical.shortHash(Canonical.of(state.probe(), Json.MAPPER)));
        parts.add("timeBasis:" + Canonical.shortHash(
                Canonical.of(state.timeBasis(), Json.MAPPER)));
        java.util.Collections.sort(parts);
        return Canonical.shortHash(String.join("|", parts));
    }
}
