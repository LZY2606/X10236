package ephys;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Evidence detectors. Refractory violations, amplitude drift and simultaneous
 *  multi-channel propagation are EVIDENCE ONLY: they may support merge/split
 *  decisions but must never be used as unit identity. Every item is
 *  machine-readable and carries units and its valid interval. */
public final class Evidence {

    private Evidence() {}

    private static ObjectNode base(String kind, String id) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("id", id);
        n.put("kind", kind);
        n.put("evidence_only", true);
        n.put("identity_use", "forbidden");
        n.put("note", "evidence only; cluster numbers are not stable identity");
        return n;
    }

    public static List<ObjectNode> refractoryViolations(String uid, List<Spike> memberSpikes,
                                                        double refractoryMs) {
        List<Spike> s = new ArrayList<>(memberSpikes);
        s.sort(Comparator.comparingDouble(Spike::tMs));
        List<ObjectNode> out = new ArrayList<>();
        for (int i = 1; i < s.size(); i++) {
            double dt = s.get(i).tMs() - s.get(i - 1).tMs();
            if (dt < refractoryMs) {
                String id = "ev-" + Json.shortHash("refractory|" + s.get(i - 1).id() + "|" + s.get(i).id());
                ObjectNode n = base("refractory_violation", id);
                n.put("unit_uid", uid);
                ArrayNode sp = n.putArray("spikes");
                sp.add(s.get(i - 1).id());
                sp.add(s.get(i).id());
                ObjectNode interval = n.putObject("interval");
                interval.put("value", dt);
                interval.put("unit", "ms");
                ObjectNode thr = n.putObject("threshold");
                thr.put("value", refractoryMs);
                thr.put("unit", "ms");
                out.add(n);
            }
        }
        return out;
    }

    public static List<ObjectNode> amplitudeDrift(String uid, List<Spike> memberSpikes,
                                                  double slopeThresholdUvPerS) {
        List<ObjectNode> out = new ArrayList<>();
        if (memberSpikes.size() < 4) return out;
        double sumT = 0, sumA = 0;
        double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE;
        for (Spike s : memberSpikes) {
            double t = s.tMs() / 1000.0;
            sumT += t;
            sumA += s.ampUv();
            tMin = Math.min(tMin, s.tMs());
            tMax = Math.max(tMax, s.tMs());
        }
        int n = memberSpikes.size();
        double mt = sumT / n, ma = sumA / n;
        double cov = 0, var = 0;
        for (Spike s : memberSpikes) {
            double t = s.tMs() / 1000.0 - mt;
            cov += t * (s.ampUv() - ma);
            var += t * t;
        }
        if (var < 1e-9) return out;
        double slope = cov / var;
        if (Math.abs(slope) > slopeThresholdUvPerS) {
            String id = "ev-" + Json.shortHash("drift|" + uid + "|" + memberSpikes.get(0).id() + "|" + n);
            ObjectNode item = base("amplitude_drift", id);
            item.put("unit_uid", uid);
            ObjectNode sl = item.putObject("slope");
            sl.put("value", slope);
            sl.put("unit", "uV/s");
            ObjectNode thr = item.putObject("threshold");
            thr.put("value", slopeThresholdUvPerS);
            thr.put("unit", "uV/s");
            ObjectNode vi = item.putObject("valid_interval");
            vi.put("t0", tMin);
            vi.put("t1", tMax);
            vi.put("unit", "ms");
            vi.put("half_open", true);
            out.add(item);
        }
        return out;
    }

    public static List<ObjectNode> propagation(List<String> uids, Map<String, List<Spike>> membersByUid,
                                               Map<String, double[]> channelPos,
                                               Map<String, String> spikeChannel,
                                               double coincidenceMs, double maxDistUm) {
        List<ObjectNode> out = new ArrayList<>();
        List<String> sorted = new ArrayList<>(uids);
        sorted.sort(String::compareTo);
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                String u1 = sorted.get(i), u2 = sorted.get(j);
                ArrayNode pairs = JsonNodeFactory.instance.arrayNode();
                int count = 0;
                for (Spike a : membersByUid.getOrDefault(u1, List.of())) {
                    for (Spike b : membersByUid.getOrDefault(u2, List.of())) {
                        double dt = Math.abs(a.tMs() - b.tMs());
                        if (dt > coincidenceMs) continue;
                        double[] pa = channelPos.get(spikeChannel.get(a.id()));
                        double[] pb = channelPos.get(spikeChannel.get(b.id()));
                        if (pa == null || pb == null) continue;
                        double dist = Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
                        if (dist > maxDistUm) continue;
                        count++;
                        if (pairs.size() < 10) {
                            ObjectNode pr = pairs.addObject();
                            pr.put("a", a.id());
                            pr.put("b", b.id());
                            ObjectNode d = pr.putObject("dt");
                            d.put("value", dt);
                            d.put("unit", "ms");
                            ObjectNode dd = pr.putObject("channel_distance");
                            dd.put("value", dist);
                            dd.put("unit", "um");
                        }
                    }
                }
                if (count > 0) {
                    String id = "ev-" + Json.shortHash("propagation|" + u1 + "|" + u2);
                    ObjectNode item = base("multichannel_propagation", id);
                    ArrayNode us = item.putArray("unit_uids");
                    us.add(u1);
                    us.add(u2);
                    item.put("pair_count", count);
                    item.set("pairs", pairs);
                    out.add(item);
                }
            }
        }
        return out;
    }
}
