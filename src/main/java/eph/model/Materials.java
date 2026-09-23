package eph.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable source layer records. These never change once admitted; changes create revisions. */
public final class Materials {

    private Materials() {
    }

    /** A raw waveform reference (the waveform bytes stay outside this database; only the citation is kept). */
    public record WaveformSource(String sourceId, String uri, String format, String sha256,
                                 long startNs, String timeBase, long sampleCount, double sampleRateHz,
                                 Map<String, String> attributes) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceId", sourceId);
            m.put("uri", uri);
            m.put("format", format);
            m.put("sha256", sha256);
            m.put("startNs", startNs);
            m.put("timeBase", timeBase);
            m.put("sampleCount", sampleCount);
            m.put("sampleRateHz", sampleRateHz);
            m.put("attributes", attributes);
            return m;
        }
    }

    /** Probe geometry channel with a coordinate in a named frame. */
    public record Channel(String channelId, String sourceId, int index, double xUm, double yUm,
                          String frame, String rawCoord) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channelId", channelId);
            m.put("sourceId", sourceId);
            m.put("index", index);
            m.put("xUm", xUm);
            m.put("yUm", yUm);
            m.put("frame", frame);
            m.put("rawCoord", rawCoord);
            return m;
        }
    }

    /** Versioned detection threshold. Revisions append; prior versions remain for replay. */
    public record ThresholdRevision(String thresholdId, String sourceId, int revision,
                                    String channelId, double levelUv, String rawLevel,
                                    String method, boolean active) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("thresholdId", thresholdId);
            m.put("sourceId", sourceId);
            m.put("revision", revision);
            m.put("channelId", channelId);
            m.put("levelUv", levelUv);
            m.put("rawLevel", rawLevel);
            m.put("method", method);
            m.put("active", active);
            return m;
        }
    }

    /** A citation into the raw waveform (sample window + channel), never the waveform itself. */
    public record WaveformSnippet(String snippetId, String eventId, String sourceId, String channelId,
                                  int sampleStart, int sampleEnd, String rawSampleRange) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("snippetId", snippetId);
            m.put("eventId", eventId);
            m.put("sourceId", sourceId);
            m.put("channelId", channelId);
            m.put("sampleStart", sampleStart);
            m.put("sampleEnd", sampleEnd);
            m.put("rawSampleRange", rawSampleRange);
            return m;
        }
    }

    /** Multi-channel detection event (spike candidate). */
    public record Detection(String eventId, String sourceId, String channelId, String thresholdId,
                            long timeNs, String timeBase, double amplitudeUv, String rawAmplitude,
                            List<String> concurrentEventIds, String snippetId, int detectionRevision) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventId", eventId);
            m.put("sourceId", sourceId);
            m.put("channelId", channelId);
            m.put("thresholdId", thresholdId);
            m.put("timeNs", timeNs);
            m.put("timeBase", timeBase);
            m.put("amplitudeUv", amplitudeUv);
            m.put("rawAmplitude", rawAmplitude);
            m.put("concurrentEventIds", concurrentEventIds);
            m.put("snippetId", snippetId);
            m.put("detectionRevision", detectionRevision);
            return m;
        }
    }
}
