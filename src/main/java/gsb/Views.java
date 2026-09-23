package gsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * View models for the review page: waveform snippets, feature projection,
 * autocorrelogram, firing rate and drift timeline. All values carry unit and
 * basis metadata; unconvertible raw values are flagged, never dropped silently.
 */
public final class Views {
    private final Store store;
    private final Deriver deriver;

    public Views(Store store, Deriver deriver) {
        this.store = store;
        this.deriver = deriver;
    }

    private static double timeS(JsonNode spike) {
        return Quantity.of(spike.get("time").get("value").asText(),
                spike.get("time").get("unit").asText(), null).normalized();
    }

    private static Double ampUv(JsonNode spike) {
        Quantity q = Quantity.of(spike.get("amplitude").get("value").asText(),
                spike.get("amplitude").get("unit").asText(), null);
        return q.convertible() ? q.normalized() : null;
    }

    private static double rateHz(JsonNode spike) {
        Quantity q = Quantity.of(spike.get("sampleRate").get("value").asText(),
                spike.get("sampleRate").get("unit").asText(), null);
        return q.convertible() ? q.normalized() : 30000.0;
    }

    /** Full per-unit view data for the five linked panels. */
    public ObjectNode unitViews(String unitId, long asOf) {
        double[] window = deriver.currentWindow();
        List<JsonNode> spikes = store.spikesForUnit(unitId, asOf);
        List<JsonNode> inWindow = new ArrayList<>();
        for (JsonNode s : spikes) {
            double t = timeS(s);
            if (t >= window[0] && t <= window[1]) inWindow.add(s);
        }
        ObjectNode out = Json.obj();

        // --- waveform panel ---
        ArrayNode waveforms = out.putArray("waveforms");
        for (JsonNode s : inWindow) {
            ObjectNode w = waveforms.addObject();
            w.put("id", s.get("id").asText());
            w.put("channel", s.get("channel").asInt());
            w.put("tS", timeS(s));
            Double a = ampUv(s);
            if (a == null) {
                w.putNull("ampUv");
                w.put("ampRaw", s.get("amplitude").get("value").asText() + " "
                        + s.get("amplitude").get("unit").asText());
            } else {
                w.put("ampUv", a);
            }
            w.put("rateHz", rateHz(s));
            w.set("samples", s.get("samples"));
        }

        // --- feature projection (all units, so the selected one is seen in context) ---
        ArrayNode projection = out.putArray("projection");
        for (JsonNode unit : store.units()) {
            String uid = unit.get("id").asText();
            for (JsonNode s : store.spikesForUnit(uid, asOf)) {
                double t = timeS(s);
                if (t < window[0] || t > window[1]) continue;
                Double a = ampUv(s);
                if (a == null) continue;
                double widthMs = spikeWidthMs(s);
                ObjectNode p = projection.addObject();
                p.put("unitId", uid);
                p.put("ampUv", a);
                p.put("widthMs", widthMs);
            }
        }

        // --- autocorrelogram ---
        List<Double> times = new ArrayList<>();
        for (JsonNode s : inWindow) times.add(timeS(s));
        times.sort(Comparator.naturalOrder());
        double refractoryMs = 1.5;
        for (JsonNode fv : store.featureVersions()) {
            if (fv.get("id").asText().equals(deriverParamVersion())) {
                refractoryMs = fv.get("params").path("refractoryMs").asDouble(1.5);
            }
        }
        int binMs = 1, nBins = 50;
        long[] acg = new long[nBins];
        for (int i = 0; i < times.size(); i++) {
            for (int j = i + 1; j < times.size(); j++) {
                double dMs = (times.get(j) - times.get(i)) * 1000.0;
                if (dMs > nBins * binMs) break;
                acg[(int) Math.ceil(dMs / binMs) - 1]++;
            }
        }
        ObjectNode acgNode = out.putObject("acg");
        acgNode.put("binMs", binMs);
        acgNode.put("refractoryMs", refractoryMs);
        ArrayNode counts = acgNode.putArray("counts");
        for (long c : acg) counts.add(c);

        // --- firing rate ---
        double binS = 10.0;
        double span = Math.max(binS, window[1] - window[0]);
        int nRate = (int) Math.ceil(span / binS);
        long[] rate = new long[nRate];
        for (double t : times) {
            int b = (int) Math.min(nRate - 1, Math.floor((t - window[0]) / binS));
            if (b >= 0) rate[b]++;
        }
        ObjectNode rateNode = out.putObject("rate");
        rateNode.put("binS", binS);
        rateNode.put("windowStartS", window[0]);
        ArrayNode rcounts = rateNode.putArray("counts");
        for (long c : rate) rcounts.add(c);

        // --- drift timeline ---
        ArrayNode drift = out.putArray("drift");
        for (JsonNode s : inWindow) {
            Double a = ampUv(s);
            ObjectNode p = drift.addObject();
            p.put("tS", timeS(s));
            if (a == null) {
                p.putNull("ampUv");
                p.put("ampRaw", s.get("amplitude").get("value").asText());
            } else {
                p.put("ampUv", a);
            }
        }

        // --- metadata: units, basis, valid interval, invalidation reasons ---
        ObjectNode meta = out.putObject("meta");
        meta.put("timeUnit", "s");
        meta.put("timeBasis", "recording");
        meta.put("amplitudeUnit", "uV");
        meta.put("windowStartS", window[0]);
        meta.put("windowEndS", window[1]);
        ArrayNode inv = meta.putArray("invalidations");
        for (JsonNode d : store.db.query(
                "SELECT * FROM derived_results WHERE unit_id=? AND status='INVALIDATED' ORDER BY seq",
                Deriver::derivedToJson, unitId)) {
            ObjectNode iv = inv.addObject();
            iv.put("seq", d.get("seq").asLong());
            iv.put("reason", d.get("invalidationReason").asText());
        }
        return out;
    }

    private String deriverParamVersion() {
        String v = store.db.getMeta("paramVersion");
        return v == null ? "fv1" : v;
    }

    /** Width of a snippet: samples between min and following max, in ms. */
    static double spikeWidthMs(JsonNode spike) {
        JsonNode samples = spike.get("samples");
        if (!samples.isArray() || samples.size() < 3) return 0.0;
        int minIdx = 0;
        for (int i = 1; i < samples.size(); i++) {
            if (samples.get(i).asDouble() < samples.get(minIdx).asDouble()) minIdx = i;
        }
        int maxIdx = minIdx;
        for (int i = minIdx + 1; i < samples.size(); i++) {
            if (samples.get(i).asDouble() > samples.get(maxIdx).asDouble()) maxIdx = i;
        }
        double rateHz = rateHz(spike);
        return (maxIdx - minIdx) / rateHz * 1000.0;
    }
}
