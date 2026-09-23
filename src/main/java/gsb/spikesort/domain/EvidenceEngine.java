package gsb.spikesort.domain;

import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Detects machine-readable evidence. Refractory violations, amplitude drift
 *  and simultaneous multi-channel propagation are EVIDENCE ONLY — they never
 *  rewrite identity. The cluster index is explicitly marked as non-identity
 *  in every evidence basis. */
public final class EvidenceEngine {
    public static final double PROPAGATION_WINDOW_MS = 0.6;
    public static final double DRIFT_RELATIVE_THRESHOLD = 0.20;

    private final Store store;

    public EvidenceEngine(Store store) { this.store = store; }

    public List<EvidenceEvent> evaluate(String adjudicationEventId) {
        List<EvidenceEvent> out = new ArrayList<>();
        DetectionParams params = store.latestDetectionParams()
                .orElseThrow(() -> new IllegalStateException("no detection params"));
        List<SpikeEvent> spikes = store.spikes();

        // group by unit (null unit ignored)
        Map<String, List<SpikeEvent>> byUnit = new TreeMap<>();
        for (SpikeEvent s : spikes) {
            if (s.unitId() != null) byUnit.computeIfAbsent(s.unitId(), k -> new ArrayList<>()).add(s);
        }

        // 1) refractory violations: ISI below the detected refractory period
        for (var e : byUnit.entrySet()) {
            List<SpikeEvent> us = e.getValue();
            for (int i = 1; i < us.size(); i++) {
                double isi = us.get(i).timeMs() - us.get(i - 1).timeMs();
                if (isi < params.refractoryMs()) {
                    out.add(evidence("REFRACTORY_VIOLATION", e.getKey(),
                            us.get(i).channel(), us.get(i).timeMs(),
                            base(adjudicationEventId)
                                    .put("isiMs", isi)
                                    .put("refractoryMs", params.refractoryMs())
                                    .put("prevSpikeId", us.get(i - 1).id())
                                    .put("spikeId", us.get(i).id())));
                }
            }
        }

        // 2) amplitude drift: relative slope over the recording window
        for (var e : byUnit.entrySet()) {
            List<SpikeEvent> us = e.getValue().stream()
                    .filter(s -> s.amplitudeUv() != null).toList();
            if (us.size() < 3) continue;
            double t0 = us.get(0).timeMs();
            double t1 = us.get(us.size() - 1).timeMs();
            if (t1 <= t0) continue;
            double meanAmp = us.stream().mapToDouble(SpikeEvent::amplitudeUv).average().orElse(0);
            if (meanAmp == 0) continue;
            // least-squares slope
            double mt = us.stream().mapToDouble(SpikeEvent::timeMs).average().orElse(0);
            double num = 0, den = 0;
            for (SpikeEvent s : us) {
                num += (s.timeMs() - mt) * (s.amplitudeUv() - meanAmp);
                den += (s.timeMs() - mt) * (s.timeMs() - mt);
            }
            double slope = den == 0 ? 0 : num / den; // uV per ms
            double relChange = Math.abs(slope) * (t1 - t0) / Math.abs(meanAmp);
            if (relChange >= DRIFT_RELATIVE_THRESHOLD) {
                out.add(evidence("AMPLITUDE_DRIFT", e.getKey(), null, null,
                        base(adjudicationEventId)
                                .put("slopeUvPerMs", slope)
                                .put("relativeChange", relChange)
                                .put("meanAmplitudeUv", meanAmp)
                                .put("windowStartMs", t0)
                                .put("windowEndMs", t1)));
            }
        }

        // 3) simultaneous multi-channel propagation (probe-adjacent sites)
        Map<Integer, ProbeGeometry.Site> sites = new TreeMap<>();
        store.latestProbeGeometry().ifPresent(g -> g.sites().forEach(s -> sites.put(s.channel(), s)));
        for (int i = 0; i < spikes.size(); i++) {
            for (int j = i + 1; j < spikes.size(); j++) {
                SpikeEvent a = spikes.get(i), b = spikes.get(j);
                double dt = b.timeMs() - a.timeMs();
                if (dt > PROPAGATION_WINDOW_MS) break;
                if (a.channel() == b.channel()) continue;
                ProbeGeometry.Site sa = sites.get(a.channel());
                ProbeGeometry.Site sb = sites.get(b.channel());
                if (sa == null || sb == null) continue;
                double distUm = Math.hypot(sa.xUm() - sb.xUm(), sa.yUm() - sb.yUm());
                if (distUm <= 60.0) {
                    out.add(evidence("MULTICHANNEL_PROPAGATION", a.unitId(), a.channel(), a.timeMs(),
                            base(adjudicationEventId)
                                    .put("channelA", a.channel()).put("channelB", b.channel())
                                    .put("deltaMs", dt).put("distanceUm", distUm)
                                    .put("spikeIdA", a.id()).put("spikeIdB", b.id())));
                }
            }
        }
        return out;
    }

    /** Chainable basis map so evidence construction reads declaratively. */
    static final class Basis extends TreeMap<String, Object> {
        public Basis put(String k, Object v) { super.put(k, v); return this; }
    }

    private static Basis base(String eventId) {
        Basis m = new Basis();
        m.put("rule", "evidence_only");
        m.put("clusterIndexIsIdentity", false);
        m.put("adjudicationEventId", eventId);
        return m;
    }

    private static EvidenceEvent evidence(String kind, String unitId, Integer channel,
                                          Double timeMs, Basis basis) {
        String basisJson = Json.write(basis);
        String id = "evi-" + Json.digestOf(kind + "|" + unitId + "|" + channel + "|" + timeMs + "|" + basisJson)
                .substring(0, 16);
        return new EvidenceEvent(id, kind, unitId, channel, timeMs, basisJson,
                (String) basis.get("adjudicationEventId"));
    }
}
