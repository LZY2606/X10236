package org.neuro.spike.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic single-linkage clustering over the 2-D projection.
 * Events are processed in stable uid order; ties therefore resolve to a
 * fixed, replayable grouping. The clustering identity (uid) is assigned by
 * the caller, this component only returns ordered member groups.
 */
public final class Clusterer {
    private Clusterer() {
    }

    public static List<List<String>> cluster(List<FeatureExtractor.Point> points, double thresholdMicrovolts) {
        List<FeatureExtractor.Point> sorted = points.stream()
                .sorted(Comparator.comparing(FeatureExtractor.Point::eventUid))
                .toList();
        int n = sorted.size();
        int[] group = new int[n];
        for (int i = 0; i < n; i++) {
            group[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = sorted.get(i).x() - sorted.get(j).x();
                double dy = sorted.get(i).y() - sorted.get(j).y();
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist <= thresholdMicrovolts) {
                    union(group, i, j);
                }
            }
        }
        java.util.Map<Integer, List<String>> groups = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) {
            int root = root(group, i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(sorted.get(i).eventUid());
        }
        List<List<String>> out = new ArrayList<>(groups.values());
        out.forEach(list -> list.sort(String::compareTo));
        out.sort(Comparator.comparing(a -> a.get(0)));
        return out;
    }

    private static void union(int[] g, int a, int b) {
        int ra = root(g, a);
        int rb = root(g, b);
        if (ra != rb) {
            g[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    private static int root(int[] g, int a) {
        while (g[a] != a) {
            g[a] = g[g[a]];
            a = g[a];
        }
        return a;
    }
}
