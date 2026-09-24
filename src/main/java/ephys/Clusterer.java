package ephys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic greedy clustering over (amplitude, channel position).
 *  Identical input always yields identical cluster order and assignments.
 *  Resource limits produce a BudgetExhausted outcome, never a silent
 *  truncation. */
public final class Clusterer {

    public record Params(String version, double epsAmpUv, double epsChanUm, double refractoryMs,
                         double driftSlopeUvPerS, double coincidenceMs, double propagationDistUm,
                         long maxSpikes, long maxClusters) {

        public static Params defaults(String version) {
            return new Params(version, 18.0, 80.0, 1.0, 30.0, 0.5, 150.0, 100_000, 256);
        }

        public static Params fromJson(JsonNode j) {
            Params d = defaults(j.hasNonNull("version") ? j.get("version").asText() : "fv?");
            return new Params(
                d.version(),
                j.hasNonNull("eps_amp_uv") ? j.get("eps_amp_uv").asDouble() : d.epsAmpUv(),
                j.hasNonNull("eps_chan_um") ? j.get("eps_chan_um").asDouble() : d.epsChanUm(),
                j.hasNonNull("refractory_ms") ? j.get("refractory_ms").asDouble() : d.refractoryMs(),
                j.hasNonNull("drift_slope_uv_per_s") ? j.get("drift_slope_uv_per_s").asDouble()
                    : d.driftSlopeUvPerS(),
                j.hasNonNull("coincidence_ms") ? j.get("coincidence_ms").asDouble() : d.coincidenceMs(),
                j.hasNonNull("propagation_dist_um") ? j.get("propagation_dist_um").asDouble()
                    : d.propagationDistUm(),
                j.hasNonNull("max_spikes") ? j.get("max_spikes").asLong() : d.maxSpikes(),
                j.hasNonNull("max_clusters") ? j.get("max_clusters").asLong() : d.maxClusters());
        }

        public ObjectNode toJson() {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            n.put("version", version);
            n.put("eps_amp_uv", epsAmpUv);
            n.put("eps_chan_um", epsChanUm);
            n.put("refractory_ms", refractoryMs);
            n.put("drift_slope_uv_per_s", driftSlopeUvPerS);
            n.put("coincidence_ms", coincidenceMs);
            n.put("propagation_dist_um", propagationDistUm);
            n.put("max_spikes", maxSpikes);
            n.put("max_clusters", maxClusters);
            return n;
        }
    }

    public record Result(LinkedHashMap<String, Integer> assignments, List<List<String>> clusters) {}

    public Outcome<Result> cluster(List<Spike> spikes, Params p, Map<String, Integer> channelIndex,
                                   List<Diag> diags) {
        if (spikes.size() > p.maxSpikes()) {
            return new Outcome.BudgetExhausted<>(
                "spike budget exhausted: " + spikes.size() + " > " + p.maxSpikes(),
                List.of(Diag.error("cluster", "/spikes",
                    "spike count " + spikes.size() + " exceeds budget " + p.maxSpikes())));
        }
        List<Spike> usable = new ArrayList<>();
        for (Spike s : spikes) {
            if (s.usable() && channelIndex.containsKey(s.channel())) {
                usable.add(s);
            } else {
                diags.add(Diag.warning("cluster", "/spikes/" + s.id(),
                    "spike excluded from clustering: unnormalized quantity or unknown channel '"
                        + s.channel() + "'"));
            }
        }
        usable.sort(Comparator.comparingDouble(Spike::tMs).thenComparing(Spike::id));

        List<double[]> centroids = new ArrayList<>();
        List<List<String>> clusters = new ArrayList<>();
        LinkedHashMap<String, Integer> assignments = new LinkedHashMap<>();
        for (Spike s : usable) {
            double fa = s.ampUv();
            double fc = channelIndex.get(s.channel()) * 50.0;
            int best = -1;
            for (int i = 0; i < centroids.size(); i++) {
                double[] c = centroids.get(i);
                double da = (fa - c[0]) / p.epsAmpUv();
                double dc = (fc - c[1]) / p.epsChanUm();
                if (da * da + dc * dc <= 1.0) {
                    best = i;
                    break;
                }
            }
            if (best < 0) {
                if (clusters.size() >= p.maxClusters()) {
                    return new Outcome.BudgetExhausted<>(
                        "cluster budget exhausted: more than " + p.maxClusters() + " clusters",
                        List.of(Diag.error("cluster", "/clusters",
                            "cluster count exceeds budget " + p.maxClusters())));
                }
                best = clusters.size();
                centroids.add(new double[] {fa, fc});
                clusters.add(new ArrayList<>());
            }
            clusters.get(best).add(s.id());
            assignments.put(s.id(), best);
            double[] c = centroids.get(best);
            int n = clusters.get(best).size();
            c[0] += (fa - c[0]) / n;
            c[1] += (fc - c[1]) / n;
        }
        return new Outcome.Ok<>(new Result(assignments, clusters), diags);
    }
}
