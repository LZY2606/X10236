package neuro.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import neuro.model.ChannelGeometry;
import neuro.model.FeaturePoint;
import neuro.model.Quantity;
import neuro.model.Spike;

/**
 * The immutable material layer, addressed by source file. Detection events, waveform references,
 * probe geometry, thresholds, feature versions and (initial) cluster labels all live here, kept
 * separate from decisions. A SOURCE_PATCH replaces one keyed source without touching the others,
 * which is what makes "local dependency change" genuinely local.
 */
public final class MaterialSet {

    public static final class Source {
        public String sourceFile;
        public final List<Spike> spikes = new ArrayList<>();
        public final Map<String, ChannelGeometry> geometry = new LinkedHashMap<>();
        public final Map<String, FeaturePoint> features = new LinkedHashMap<>();
        public Quantity threshold;
        public String detectionParamVersion;
        public String featureVersion;
        public double refractoryS;
        public String timeBasis;
    }

    private final Map<String, Source> sources = new LinkedHashMap<>();

    public Source source(String file) {
        return sources.computeIfAbsent(file, f -> {
            Source s = new Source();
            s.sourceFile = f;
            return s;
        });
    }

    public void replace(Source s) {
        sources.put(s.sourceFile, s);
    }

    public boolean has(String file) { return sources.containsKey(file); }

    public Map<String, Source> sources() { return sources; }

    public List<Spike> allSpikes() {
        List<Spike> all = new ArrayList<>();
        for (Source s : sources.values()) all.addAll(s.spikes);
        return all;
    }

    public Spike spike(String id) {
        for (Source s : sources.values())
            for (Spike sp : s.spikes) if (sp.id().equals(id)) return sp;
        return null;
    }

    public ChannelGeometry geometry(String channel) {
        for (Source s : sources.values()) {
            ChannelGeometry g = s.geometry.get(channel);
            if (g != null) return g;
        }
        return null;
    }

    public FeaturePoint feature(String spikeId) {
        for (Source s : sources.values()) {
            FeaturePoint f = s.features.get(spikeId);
            if (f != null) return f;
        }
        return null;
    }
}
