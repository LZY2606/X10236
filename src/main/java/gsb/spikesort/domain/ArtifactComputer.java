package gsb.spikesort.domain;

import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Computes derived artifacts. Every artifact records its input digest,
 *  parameter version and parent dependencies; artifact identity is
 *  content-derived so equivalent inputs yield stable ids and order. */
public final class ArtifactComputer {
    public static final double ACG_BIN_MS = 1.0;
    public static final int ACG_BINS = 50;
    public static final double RATE_BIN_MS = 1000.0;

    private final Store store;

    public ArtifactComputer(Store store) { this.store = store; }

    /** Compute the full current artifact set for the given units at an event seq. */
    public List<Artifact> computeAll(List<Unit> units, long fromSeq) {
        List<Artifact> out = new ArrayList<>();
        out.add(featureProjection(fromSeq));
        for (Unit u : units) out.addAll(computeForUnit(u.id(), fromSeq));
        return out;
    }

    /** Per-unit artifacts: waveform summary, autocorrelogram, firing rate, drift timeline. */
    public List<Artifact> computeForUnit(String unitId, long fromSeq) {
        List<SpikeEvent> spikes = store.spikesOfUnit(unitId);
        DetectionParams params = store.latestDetectionParams().orElseThrow();
        List<Artifact> out = new ArrayList<>();
        out.add(waveformSummary(unitId, spikes, params, fromSeq));
        out.add(autocorrelogram(unitId, spikes, params, fromSeq));
        out.add(firingRate(unitId, spikes, params, fromSeq));
        out.add(driftTimeline(unitId, spikes, params, fromSeq));
        return out;
    }

    private String inputDigest(List<SpikeEvent> spikes) {
        StringBuilder sb = new StringBuilder();
        for (SpikeEvent s : spikes) {
            sb.append(s.id()).append('|').append(s.timeMs()).append('|').append(s.channel())
              .append('|').append(s.amplitudeRaw()).append('|').append(s.unitId()).append('\n');
        }
        return Json.digestOf(sb.toString());
    }

    private List<String> parents(DetectionParams params, List<SpikeEvent> spikes) {
        List<String> p = new ArrayList<>();
        p.add(params.id());
        store.featureSets().stream().reduce((a, b) -> b).ifPresent(f -> p.add(f.id()));
        spikes.stream().map(SpikeEvent::waveformRefId).filter(w -> w != null)
                .distinct().sorted().forEach(p::add);
        return p;
    }

    private Artifact artifact(String kind, String unitId, String inputDigest,
                              DetectionParams params, List<String> parents,
                              long fromSeq, Map<String, Object> payload) {
        String id = "art-" + Json.digestOf(kind + "|" + unitId + "|" + inputDigest + "|" + params.version())
                .substring(0, 16);
        return new Artifact(id, kind, unitId, inputDigest, params.version(), parents,
                fromSeq, null, "valid", null, Json.write(payload));
    }

    private Artifact waveformSummary(String unitId, List<SpikeEvent> spikes,
                                     DetectionParams params, long fromSeq) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("unit", "uV");
        payload.put("timeBase", "recording_ms");
        payload.put("spikeCount", spikes.size());
        List<Double> amps = spikes.stream().filter(s -> s.amplitudeUv() != null)
                .map(SpikeEvent::amplitudeUv).toList();
        payload.put("meanAmplitudeUv", amps.stream().mapToDouble(d -> d).average().orElse(Double.NaN));
        payload.put("unconvertibleAmplitudes", spikes.stream()
                .filter(s -> s.amplitudeUv() == null).map(SpikeEvent::id).toList());
        return artifact("WAVEFORM_SUMMARY", unitId, inputDigest(spikes), params,
                parents(params, spikes), fromSeq, payload);
    }

    private Artifact autocorrelogram(String unitId, List<SpikeEvent> spikes,
                                     DetectionParams params, long fromSeq) {
        long[] bins = new long[ACG_BINS];
        for (int i = 0; i < spikes.size(); i++) {
            for (int j = i + 1; j < spikes.size(); j++) {
                double dt = spikes.get(j).timeMs() - spikes.get(i).timeMs();
                if (dt >= ACG_BIN_MS * ACG_BINS) break;
                bins[(int) (dt / ACG_BIN_MS)]++;
            }
        }
        Map<String, Object> payload = new TreeMap<>();
        payload.put("binMs", ACG_BIN_MS);
        payload.put("timeBase", "recording_ms");
        payload.put("refractoryMs", params.refractoryMs());
        payload.put("counts", bins);
        return artifact("ACG", unitId, inputDigest(spikes), params,
                parents(params, spikes), fromSeq, payload);
    }

    private Artifact firingRate(String unitId, List<SpikeEvent> spikes,
                                DetectionParams params, long fromSeq) {
        int nBins = (int) Math.ceil((params.windowEndMs() - params.windowStartMs()) / RATE_BIN_MS);
        long[] bins = new long[Math.max(nBins, 1)];
        for (SpikeEvent s : spikes) {
            int b = (int) ((s.timeMs() - params.windowStartMs()) / RATE_BIN_MS);
            if (b >= 0 && b < bins.length) bins[b]++;
        }
        Map<String, Object> payload = new TreeMap<>();
        payload.put("binMs", RATE_BIN_MS);
        payload.put("timeBase", "recording_ms");
        payload.put("windowStartMs", params.windowStartMs());
        payload.put("windowEndMs", params.windowEndMs());
        payload.put("counts", bins);
        return artifact("FIRING_RATE", unitId, inputDigest(spikes), params,
                parents(params, spikes), fromSeq, payload);
    }

    private Artifact driftTimeline(String unitId, List<SpikeEvent> spikes,
                                   DetectionParams params, long fromSeq) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (SpikeEvent s : spikes) {
            if (s.amplitudeUv() == null) continue;
            Map<String, Object> pt = new TreeMap<>();
            pt.put("tMs", s.timeMs());
            pt.put("amplitudeUv", s.amplitudeUv());
            pt.put("spikeId", s.id());
            points.add(pt);
        }
        Map<String, Object> payload = new TreeMap<>();
        payload.put("unit", "uV");
        payload.put("timeBase", "recording_ms");
        payload.put("points", points);
        return artifact("DRIFT_TIMELINE", unitId, inputDigest(spikes), params,
                parents(params, spikes), fromSeq, payload);
    }

    /** Global feature projection (amplitude vs snippet width), one point per spike. */
    public Artifact featureProjection(long fromSeq) {
        List<SpikeEvent> spikes = store.spikes();
        DetectionParams params = store.latestDetectionParams().orElseThrow();
        Map<String, WaveformRef> refs = new TreeMap<>();
        for (WaveformRef w : store.waveformRefs()) refs.put(w.id(), w);
        List<Map<String, Object>> points = new ArrayList<>();
        for (SpikeEvent s : spikes) {
            if (s.amplitudeUv() == null) continue;
            WaveformRef w = s.waveformRefId() == null ? null : refs.get(s.waveformRefId());
            Map<String, Object> pt = new TreeMap<>();
            pt.put("spikeId", s.id());
            pt.put("unitId", s.unitId());
            pt.put("xAmplitudeUv", s.amplitudeUv());
            pt.put("yWidthMs", w == null ? null : (w.tEndMs() - w.tStartMs()));
            points.add(pt);
        }
        Map<String, Object> payload = new TreeMap<>();
        payload.put("xUnit", "uV");
        payload.put("yUnit", "ms");
        payload.put("timeBase", "recording_ms");
        payload.put("points", points);
        return artifact("FEATURE_PROJECTION", null, inputDigest(spikes), params,
                parents(params, spikes), fromSeq, payload);
    }
}
