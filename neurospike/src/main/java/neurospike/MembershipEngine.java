package neurospike;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Derives cluster generations and lineage edges from a State.
 *
 * The current generation is computed by replaying baseline labels and every
 * SPLIT/MERGE decision in event order. Between label-set revisions, generations
 * are matched on detection overlap so renumbering is visible but never changes
 * the stable identity (hash of sorted detection ids + label version at origin).
 */
public final class MembershipEngine {

    private MembershipEngine() {
    }

    public record MembershipResult(List<ClusterGen> clusters, List<LineageEdge> edges,
                                   List<Diagnostic> diagnostics, String labelVersion,
                                   String labelRevEvent) {
    }

    public static MembershipResult at(State state, long seq) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        MaterialRev<LabelSet> labelsRev = state.labelsAtOrBefore(seq);
        LabelSet labels = labelsRev.material();
        Map<String, MaterialRev<Detection>> detRevs = state.detectionAtOrBefore(seq);

        Map<Integer, Set<String>> initial = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : labels.detectionCluster().entrySet()) {
            if (!detRevs.containsKey(e.getKey())) {
                diagnostics.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                        "/labelSets/" + labels.labelVersion()
                                + "/detectionCluster/" + e.getKey(),
                        "label references unknown or out-of-scope detection", e.getKey()));
                continue;
            }
            if (!detRevs.get(e.getKey()).material().active()) {
                continue;
            }
            initial.computeIfAbsent(e.getValue(), k -> new TreeSet<>()).add(e.getKey());
        }

        List<ClusterGen> gens = new ArrayList<>();
        Map<Integer, ClusterGen> byNumber = new HashMap<>();
        long labelSeq = state.events().stream()
                .filter(e -> e.eventId().equals(labelsRev.introducedByEvent()))
                .mapToLong(Event::seq).findFirst().orElse(1L);
        for (Map.Entry<Integer, Set<String>> e : sortedByContent(initial)) {
            List<String> members = List.copyOf(e.getValue());
            String stable = stableId(members, labels.labelVersion());
            ClusterGen gen = new ClusterGen(stable, e.getKey(), labels.labelVersion(),
                    members, labelsRev.introducedByEvent());
            gens.add(gen);
            byNumber.put(e.getKey(), gen);
        }

        List<LineageEdge> edges = new ArrayList<>(carryEdges(gens, labelsRev, labelSeq));
        List<ClusterGen> previousGens = new ArrayList<>(gens);

        // apply label revisions as renumber/match steps
        for (MaterialRev<LabelSet> rev : state.labelSetVersions()) {
            long revSeq = state.events().stream()
                    .filter(e -> e.eventId().equals(rev.introducedByEvent()))
                    .mapToLong(Event::seq).findFirst().orElse(Long.MAX_VALUE);
            if (revSeq <= labelSeq || revSeq > seq) {
                continue;
            }
            MatchStep step = applyLabelRevision(state, rev, previousGens, detRevs,
                    diagnostics);
            previousGens = step.gens();
            edges.addAll(step.edges());
        }

        // replay explicit split/merge decisions in order
        for (Decision d : state.decisionsAtOrBefore(seq)) {
            if (d.verdict().equals(Decision.SPLIT)) {
                SplitStep step = applySplit(state, d, previousGens, edges);
                previousGens = step.gens();
            } else if (d.verdict().equals(Decision.MERGE)) {
                MergeStep step = applyMerge(state, d, previousGens);
                previousGens = step.gens();
            }
        }

        previousGens.sort(Comparator.comparing(ClusterGen::stableId));
        return new MembershipResult(List.copyOf(previousGens), List.copyOf(edges),
                diagnostics, labels.labelVersion(), labelsRev.introducedByEvent());
    }

    public static String stableId(List<String> detections, String labelVersion) {
        List<String> sorted = new ArrayList<>(detections);
        java.util.Collections.sort(sorted);
        return "cl_" + Canonical.shortHash(
                Canonical.of(Json.NODE.objectNode()
                        .put("kind", "cluster")
                        .put("labelVersion", labelVersion)
                        .set("detections", Json.NODE.arrayNode().addAll(
                                sorted.stream().map(Json.NODE::textNode).toList()))));
    }

    private static List<LineageEdge> carryEdges(List<ClusterGen> gens,
                                                MaterialRev<LabelSet> rev, long seq) {
        List<LineageEdge> out = new ArrayList<>();
        for (ClusterGen g : gens) {
            out.add(new LineageEdge(null, g.stableId(), LineageEdge.CARRY, seq,
                    rev.introducedByEvent(), g.detections()));
        }
        return out;
    }

    private record MatchStep(List<ClusterGen> gens, List<LineageEdge> edges) {
    }

    private static MatchStep applyLabelRevision(State state, MaterialRev<LabelSet> rev,
                                                List<ClusterGen> previous,
                                                Map<String, MaterialRev<Detection>> detRevs,
                                                List<Diagnostic> diagnostics) {
        long seq = state.events().stream()
                .filter(e -> e.eventId().equals(rev.introducedByEvent()))
                .mapToLong(Event::seq).findFirst().orElseThrow();
        Map<Integer, Set<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : rev.material().detectionCluster().entrySet()) {
            MaterialRev<Detection> dr = detRevs.get(e.getKey());
            if (dr == null) {
                diagnostics.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                        "/labelSets/" + rev.material().labelVersion()
                                + "/detectionCluster/" + e.getKey(),
                        "label references unknown detection", e.getKey()));
                continue;
            }
            if (!dr.material().active()) {
                continue;
            }
            groups.computeIfAbsent(e.getValue(), k -> new TreeSet<>()).add(e.getKey());
        }
        List<ClusterGen> next = new ArrayList<>();
        List<LineageEdge> edges = new ArrayList<>();
        Set<String> consumed = new TreeSet<>();
        for (Map.Entry<Integer, Set<String>> e : sortedByContent(groups)) {
            List<String> members = List.copyOf(e.getValue());
            ClusterGen best = bestOverlap(previous, members, consumed);
            String stable = best != null ? best.stableId()
                    : stableId(members, rev.material().labelVersion());
            next.add(new ClusterGen(stable, e.getKey(), rev.material().labelVersion(),
                    members, rev.introducedByEvent()));
            if (best != null) {
                String type = best.clusterNumber() == e.getKey()
                        ? LineageEdge.CARRY : LineageEdge.RENUMBER;
                edges.add(new LineageEdge(best.stableId(), stable, type, seq,
                        rev.introducedByEvent(), overlap(best.detections(), members)));
            }
        }
        // merges: two previous gens map to same new gen already captured by best;
        // detect many-to-one explicitly
        Map<String, List<ClusterGen>> byStable = new LinkedHashMap<>();
        for (ClusterGen g : next) {
            byStable.computeIfAbsent(g.stableId(), k -> new ArrayList<>());
        }
        for (ClusterGen old : previous) {
            if (next.stream().noneMatch(n -> n.stableId().equals(old.stableId()))) {
                ClusterGen target = bestOverlap(next, old.detections(), new TreeSet<>());
                if (target != null) {
                    edges.add(new LineageEdge(old.stableId(), target.stableId(),
                            LineageEdge.MERGE, seq, rev.introducedByEvent(),
                            overlap(old.detections(), target.detections())));
                }
            }
        }
        return new MatchStep(next, edges);
    }

    private record SplitStep(List<ClusterGen> gens) {
    }

    @SuppressWarnings("unchecked")
    private static SplitStep applySplit(State state, Decision d,
                                        List<ClusterGen> previous,
                                        List<LineageEdge> edges) {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        JsonNode params = d.params();
        params.path("groupA").forEach(n -> a.add(n.asText()));
        params.path("groupB").forEach(n -> b.add(n.asText()));
        java.util.Collections.sort(a);
        java.util.Collections.sort(b);
        List<ClusterGen> next = new ArrayList<>(previous);
        next.removeIf(c -> c.stableId().equals(d.targetId()));
        ClusterGen parent = previous.stream()
                .filter(c -> c.stableId().equals(d.targetId())).findFirst().orElse(null);
        String labelVersion = parent != null ? parent.labelVersion()
                : state.currentLabels().labelVersion();
        String idA = stableId(a, labelVersion + ":split:" + d.seq());
        String idB = stableId(b, labelVersion + ":split:" + d.seq());
        int numBase = 10_000 + (int) d.seq() * 2;
        next.add(new ClusterGen(idA, numBase, labelVersion, List.copyOf(a), d.eventId()));
        next.add(new ClusterGen(idB, numBase + 1, labelVersion, List.copyOf(b), d.eventId()));
        List<String> shared = parent != null
                ? overlap(parent.detections(), concat(a, b)) : List.of();
        edges.add(new LineageEdge(d.targetId(), idA, LineageEdge.SPLIT, d.seq(),
                d.eventId(), overlapOrAll(parent, a)));
        edges.add(new LineageEdge(d.targetId(), idB, LineageEdge.SPLIT, d.seq(),
                d.eventId(), overlapOrAll(parent, b)));
        return new SplitStep(next);
    }

    private record MergeStep(List<ClusterGen> gens) {
    }

    @SuppressWarnings("unchecked")
    private static MergeStep applyMerge(State state, Decision d,
                                        List<ClusterGen> previous) {
        List<String> members = new ArrayList<>();
        d.params().path("members").forEach(n -> members.add(n.asText()));
        java.util.Collections.sort(members);
        String labelVersion = state.currentLabels().labelVersion();
        String stable = stableId(members, labelVersion + ":merge:" + d.seq());
        List<ClusterGen> next = new ArrayList<>(previous);
        int numBase = 20_000 + (int) d.seq();
        next.add(new ClusterGen(stable, numBase, labelVersion, List.copyOf(members),
                d.eventId()));
        return new MergeStep(next);
    }

    private static List<String> overlapOrAll(ClusterGen parent, List<String> child) {
        if (parent == null) {
            return List.copyOf(child);
        }
        return overlap(parent.detections(), child);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static List<String> overlap(List<String> a, List<String> b) {
        Set<String> other = new TreeSet<>(b);
        List<String> out = new ArrayList<>();
        for (String s : a) {
            if (other.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static ClusterGen bestOverlap(List<ClusterGen> gens, List<String> members,
                                          Set<String> consumedStableIds) {
        Set<String> target = new TreeSet<>(members);
        ClusterGen best = null;
        int bestCount = 0;
        for (ClusterGen g : gens) {
            int count = 0;
            for (String m : g.detections()) {
                if (target.contains(m)) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = g;
                bestCount = count;
            }
        }
        return best;
    }

    private static List<Map.Entry<Integer, Set<String>>> sortedByContent(
            Map<Integer, Set<String>> map) {
        List<Map.Entry<Integer, Set<String>>> list = new ArrayList<>(map.entrySet());
        list.sort(Comparator
                .<Map.Entry<Integer, Set<String>>>comparingInt(e -> -e.getValue().size())
                .thenComparing(e -> new TreeSet<>(e.getValue()).iterator().next())
                .thenComparingInt(Map.Entry::getKey));
        return list;
    }
}
