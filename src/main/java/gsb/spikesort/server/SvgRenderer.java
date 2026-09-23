package gsb.spikesort.server;

import com.fasterxml.jackson.databind.JsonNode;
import gsb.spikesort.model.Json;
import gsb.spikesort.model.Model.*;
import gsb.spikesort.store.Store;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Server-side SVG for the five linked review views. Every chart annotates
 *  units, coordinate/time bases, validity ranges and invalidation reasons. */
public final class SvgRenderer {
    private final Store store;

    private static final String[] PALETTE = {"#2563eb", "#dc2626", "#059669", "#d97706",
            "#7c3aed", "#0891b2", "#be185d", "#4d7c0f"};

    public SvgRenderer(Store store) { this.store = store; }

    private String colorOf(String unitId, List<Unit> units) {
        if (unitId == null) return "#9ca3af";
        int i = 0;
        for (Unit u : units) { if (u.id().equals(unitId)) return PALETTE[i % PALETTE.length]; i++; }
        return "#9ca3af";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String header(int w, int h) {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 " + w + " " + h
                + "' font-family='ui-monospace,monospace' font-size='10'>";
    }

    /** Waveform strips per channel: spike markers at (time, amplitude). */
    public String waveforms(long seq) {
        List<SpikeEvent> spikes = store.spikes();
        List<Unit> units = store.units();
        DetectionParams p = store.latestDetectionParams().orElseThrow();
        int w = 760, stripH = 52, pad = 26;
        Map<Integer, List<SpikeEvent>> byCh = new TreeMap<>();
        for (SpikeEvent s : spikes) byCh.computeIfAbsent(s.channel(), k -> new java.util.ArrayList<>()).add(s);
        int h = pad + byCh.size() * (stripH + 6) + 14;
        StringBuilder sb = new StringBuilder(header(w, h));
        sb.append("<text x='4' y='12' fill='#374151'>波形片段 · amplitude µV · timeBase recording_ms · window [")
          .append(p.windowStartMs()).append(", ").append(p.windowEndMs()).append(") ms</text>");
        double t0 = p.windowStartMs(), t1 = p.windowEndMs();
        int row = 0;
        for (var e : byCh.entrySet()) {
            int y0 = pad + row * (stripH + 6);
            sb.append("<rect x='60' y='").append(y0).append("' width='").append(w - 70)
              .append("' height='").append(stripH).append("' fill='#f8fafc' stroke='#e5e7eb'/>");
            sb.append("<text x='4' y='").append(y0 + stripH / 2).append("' fill='#6b7280'>ch")
              .append(e.getKey()).append("</text>");
            double maxAmp = e.getValue().stream().filter(s -> s.amplitudeUv() != null)
                    .mapToDouble(SpikeEvent::amplitudeUv).max().orElse(200);
            for (SpikeEvent s : e.getValue()) {
                double x = 60 + (w - 70) * (s.timeMs() - t0) / (t1 - t0);
                double amp = s.amplitudeUv() == null ? maxAmp * 0.3 : s.amplitudeUv();
                double bh = Math.max(4, stripH * 0.9 * amp / maxAmp);
                String color = s.amplitudeUv() == null ? "#9ca3af" : colorOf(s.unitId(), units);
                sb.append("<rect class='spk' data-spike-id='").append(esc(s.id()))
                  .append("' x='").append(String.format("%.1f", x - 1.5)).append("' y='")
                  .append(y0 + stripH - bh).append("' width='3' height='")
                  .append(String.format("%.1f", bh)).append("' fill='").append(color).append("'>")
                  .append("<title>").append(esc(s.id())).append(" · ").append(esc(s.amplitudeRaw()))
                  .append(" · ").append(s.timeMs()).append(" ms</title></rect>");
            }
            row++;
        }
        return sb.append("</svg>").toString();
    }

    /** Feature projection scatter from the FEATURE_PROJECTION artifact. */
    public String featureProjection(long seq) {
        Artifact a = artifactAt("FEATURE_PROJECTION", null, seq);
        if (a == null) return empty("no feature projection at seq " + seq);
        JsonNode payload = Json.read(a.payloadJson());
        List<Unit> units = store.units();
        int w = 380, h = 300, m = 34;
        StringBuilder sb = new StringBuilder(header(w, h));
        sb.append("<text x='4' y='12' fill='#374151'>特征投影 · x µV / y ms · param v")
          .append(a.paramVersion()).append("</text>");
        sb.append(artifactBadge(a, w));
        JsonNode pts = payload.path("points");
        double maxX = 1, maxY = 1;
        for (JsonNode pt : pts) {
            maxX = Math.max(maxX, pt.path("xAmplitudeUv").asDouble());
            maxY = Math.max(maxY, pt.path("yWidthMs").asDouble(1));
        }
        sb.append("<rect x='").append(m).append("' y='18' width='").append(w - m - 8)
          .append("' height='").append(h - m - 18).append("' fill='#f8fafc' stroke='#e5e7eb'/>");
        for (JsonNode pt : pts) {
            double x = m + (w - m - 8) * pt.path("xAmplitudeUv").asDouble() / maxX;
            double y = 18 + (h - m - 18) * (1 - pt.path("yWidthMs").asDouble(0) / maxY);
            String uid = pt.path("unitId").isNull() ? null : pt.path("unitId").asText();
            sb.append("<circle class='spk' data-spike-id='").append(esc(pt.path("spikeId").asText()))
              .append("' cx='").append(String.format("%.1f", x)).append("' cy='")
              .append(String.format("%.1f", y)).append("' r='3' fill='").append(colorOf(uid, units))
              .append("' fill-opacity='0.75'><title>").append(esc(pt.path("spikeId").asText()))
              .append("</title></circle>");
        }
        sb.append("<text x='").append(m).append("' y='").append(h - 6)
          .append("' fill='#6b7280'>x: amplitude (µV) · y: snippet width (ms)</text>");
        return sb.append("</svg>").toString();
    }

    /** Autocorrelogram bar chart for one unit. */
    public String acg(String unitId, long seq) {
        Artifact a = artifactAt("ACG", unitId, seq);
        if (a == null) return empty("no ACG for " + unitId + " at seq " + seq);
        JsonNode payload = Json.read(a.payloadJson());
        int w = 380, h = 240, m = 34;
        StringBuilder sb = new StringBuilder(header(w, h));
        sb.append("<text x='4' y='12' fill='#374151'>自相关 · ").append(esc(unitId))
          .append(" · bin ").append(payload.path("binMs").asDouble()).append(" ms · refractory ")
          .append(payload.path("refractoryMs").asDouble()).append(" ms</text>");
        sb.append(artifactBadge(a, w));
        JsonNode counts = payload.path("counts");
        long max = 1;
        for (JsonNode c : counts) max = Math.max(max, c.asLong());
        double bw = (w - m - 8.0) / counts.size();
        for (int i = 0; i < counts.size(); i++) {
            long v = counts.get(i).asLong();
            double bh = (h - m - 24.0) * v / max;
            boolean inRefractory = (i * payload.path("binMs").asDouble()) < payload.path("refractoryMs").asDouble();
            sb.append("<rect x='").append(String.format("%.1f", m + i * bw)).append("' y='")
              .append(String.format("%.1f", 18 + (h - m - 24) - bh)).append("' width='")
              .append(String.format("%.1f", Math.max(1, bw - 1))).append("' height='")
              .append(String.format("%.1f", bh)).append("' fill='")
              .append(inRefractory ? "#dc2626" : "#2563eb").append("'/>");
        }
        sb.append("<text x='").append(m).append("' y='").append(h - 6)
          .append("' fill='#6b7280'>ISI ms (red = inside refractory period)</text>");
        return sb.append("</svg>").toString();
    }

    /** Firing rate over the detection window. */
    public String firingRate(String unitId, long seq) {
        Artifact a = artifactAt("FIRING_RATE", unitId, seq);
        if (a == null) return empty("no firing rate for " + unitId + " at seq " + seq);
        JsonNode payload = Json.read(a.payloadJson());
        int w = 380, h = 240, m = 34;
        StringBuilder sb = new StringBuilder(header(w, h));
        sb.append("<text x='4' y='12' fill='#374151'>发放率 · ").append(esc(unitId))
          .append(" · bin ").append(payload.path("binMs").asDouble()).append(" ms · window [")
          .append(payload.path("windowStartMs").asDouble()).append(", ")
          .append(payload.path("windowEndMs").asDouble()).append(") ms</text>");
        sb.append(artifactBadge(a, w));
        JsonNode counts = payload.path("counts");
        long max = 1;
        for (JsonNode c : counts) max = Math.max(max, c.asLong());
        double bw = (w - m - 8.0) / counts.size();
        for (int i = 0; i < counts.size(); i++) {
            double bh = (h - m - 24.0) * counts.get(i).asLong() / max;
            sb.append("<rect x='").append(String.format("%.1f", m + i * bw)).append("' y='")
              .append(String.format("%.1f", 18 + (h - m - 24) - bh)).append("' width='")
              .append(String.format("%.1f", Math.max(1, bw - 1))).append("' height='")
              .append(String.format("%.1f", bh)).append("' fill='#059669'/>");
        }
        sb.append("<text x='").append(m).append("' y='").append(h - 6)
          .append("' fill='#6b7280'>spikes / bin · timeBase recording_ms</text>");
        return sb.append("</svg>").toString();
    }

    /** Drift timeline: amplitude vs time with evidence markers. */
    public String drift(String unitId, long seq) {
        Artifact a = artifactAt("DRIFT_TIMELINE", unitId, seq);
        if (a == null) return empty("no drift timeline for " + unitId + " at seq " + seq);
        JsonNode payload = Json.read(a.payloadJson());
        List<EvidenceEvent> evs = store.evidence().stream()
                .filter(e -> unitId.equals(e.unitId())).toList();
        int w = 380, h = 240, m = 34;
        StringBuilder sb = new StringBuilder(header(w, h));
        sb.append("<text x='4' y='12' fill='#374151'>漂移时间线 · ").append(esc(unitId))
          .append(" · µV vs recording_ms</text>");
        sb.append(artifactBadge(a, w));
        JsonNode pts = payload.path("points");
        double maxT = 1, maxA = 1;
        for (JsonNode pt : pts) {
            maxT = Math.max(maxT, pt.path("tMs").asDouble());
            maxA = Math.max(maxA, pt.path("amplitudeUv").asDouble());
        }
        StringBuilder line = new StringBuilder();
        for (JsonNode pt : pts) {
            double x = m + (w - m - 8.0) * pt.path("tMs").asDouble() / maxT;
            double y = 18 + (h - m - 24.0) * (1 - pt.path("amplitudeUv").asDouble() / maxA);
            line.append(line.length() == 0 ? "M" : "L");
            line.append(String.format("%.1f %.1f ", x, y));
            sb.append("<circle class='spk' data-spike-id='").append(esc(pt.path("spikeId").asText()))
              .append("' cx='").append(String.format("%.1f", x)).append("' cy='")
              .append(String.format("%.1f", y)).append("' r='2.5' fill='#d97706'/>");
        }
        sb.append("<path d='").append(line).append("' stroke='#d97706' fill='none' stroke-opacity='0.4'/>");
        for (EvidenceEvent ev : evs) {
            if (ev.timeMs() == null) continue;
            double x = m + (w - m - 8.0) * ev.timeMs() / maxT;
            sb.append("<line x1='").append(String.format("%.1f", x)).append("' y1='18' x2='")
              .append(String.format("%.1f", x)).append("' y2='").append(h - m)
              .append("' stroke='#dc2626' stroke-dasharray='2 2'><title>")
              .append(esc(ev.kind())).append("</title></line>");
        }
        sb.append("<text x='").append(m).append("' y='").append(h - 6)
          .append("' fill='#6b7280'>amplitude µV over time · red dashes = evidence events</text>");
        return sb.append("</svg>").toString();
    }

    private Artifact artifactAt(String kind, String unitId, long seq) {
        return store.artifactsAt(seq).stream()
                .filter(a -> a.kind().equals(kind)
                        && (unitId == null ? a.unitId() == null : unitId.equals(a.unitId())))
                .findFirst().orElse(null);
    }

    private static String artifactBadge(Artifact a, int w) {
        StringBuilder sb = new StringBuilder();
        sb.append("<text x='").append(w - 8).append("' y='12' text-anchor='end' fill='#9ca3af'>")
          .append(esc(a.id())).append(" · in digest ").append(a.inputDigest(), 0, 8)
          .append(" · valid from evt#").append(a.validFromSeq());
        if (a.validToSeq() != null) sb.append(" to evt#").append(a.validToSeq());
        sb.append("</text>");
        if (a.invalidationReason() != null) {
            sb.append("<text x='").append(w - 8).append("' y='24' text-anchor='end' fill='#dc2626'>invalidated: ")
              .append(esc(a.invalidationReason())).append("</text>");
        }
        return sb.toString();
    }

    private static String empty(String msg) {
        return header(380, 60) + "<text x='8' y='30' fill='#9ca3af'>" + esc(msg) + "</text></svg>";
    }
}
