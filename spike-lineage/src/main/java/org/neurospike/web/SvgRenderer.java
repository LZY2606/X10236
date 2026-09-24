package org.neurospike.web;

import org.neurospike.model.Material;
import org.neurospike.store.ProjectStore;

import java.util.List;

/** 服务端 SVG 渲染（业务图形不放在浏览器脚本里）。所有轴注明单位/时间基准。 */
public final class SvgRenderer {
    private final ProjectStore store;

    public SvgRenderer(ProjectStore store) { this.store = store; }

    public String probeGeometry(String probeId) {
        try {
            var chs = store.material().channels(probeId);
            StringBuilder sb = head("Probe geometry — coordinates [um], origin: probe-tip", 220, 260);
            sb.append("<line x1='60' y1='15' x2='60' y2='235' stroke='#888'/>");
            for (var ch : chs) {
                double y = 220 - ch.y() / 20.0 * 24;
                sb.append(String.format(
                        "<g><circle cx='60' cy='%.1f' r='7' fill='#3b82f6' data-id='%s'/>"
                        + "<text x='78' y='%.1f' class='lab'>%s (x=%.0f,y=%.0f um)</text></g>",
                        y, ch.stableId(), y + 4, ch.label(), ch.x(), ch.y()));
            }
            sb.append("<text x='30' y='250' class='note'>20 um pitch</text>");
            return tail(sb);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public String waveform(String waveformId) {
        try {
            Material.Waveform w = store.material().waveform(waveformId);
            if (w == null) return errorSvg("waveform missing: " + waveformId);
            double[] uV = w.physicalMicrovolts();
            StringBuilder sb = head("Waveform " + waveformId, 360, 200);
            double min = -140, max = 60;
            sb.append("<line x1='30' y1='100' x2='350' y2='100' stroke='#ccc'/>");
            StringBuilder path = new StringBuilder();
            double rateHz = w.sampleRateHz();
            for (int i = 0; i < uV.length; i++) {
                double tMs = i / rateHz * 1000.0;
                double x = 30 + tMs / 1.6 * 320;
                double y = 100 - (uV[i] - min) / (max - min) * 150;
                path.append(i == 0 ? "M" : "L").append(String.format("%.1f,%.1f ", x, y));
            }
            sb.append("<path d='").append(path).append("' fill='none' stroke='#dc2626' stroke-width='1.5'/>");
            sb.append(axes("time [ms]", "amplitude [uV]"));
            sb.append(String.format("<text x='30' y='185' class='note'>%d samples @ %.0f Hz; gain 1 ADC=1 uV; sha=%s</text>",
                    w.sampleCount(), rateHz, w.sha256().substring(0, 12)));
            return tail(sb);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public String projection(String labelSetId) {
        try {
            var clusters = store.labels().clusters(labelSetId);
            var events = store.material().events();
            var byId = new java.util.HashMap<String, Material.SpikeEvent>();
            events.forEach(e -> byId.put(e.eventId(), e));
            String[] palette = {"#2563eb", "#16a34a", "#dc2626", "#9333ea", "#ea580c", "#0891b2"};
            StringBuilder sb = head("Feature projection — " + labelSetId, 340, 300);
            sb.append("<rect x='40' y='20' width='270' height='220' fill='none' stroke='#ccc'/>");
            int ci = 0;
            for (var e2 : clusters.entrySet()) {
                String color = palette[ci++ % palette.length];
                for (String eid : e2.getValue().eventIds()) {
                    Material.SpikeEvent ev = byId.get(eid);
                    if (ev == null || !ev.valid() || ev.features().size() < 2) continue;
                    double x = 40 + (ev.features().get(0) - 1.8) / 0.35 * 270;
                    double y = 240 - ev.features().get(1) * 200 - 20;
                    sb.append(String.format(
                            "<circle cx='%.1f' cy='%.1f' r='3.5' fill='%s' data-id='%s'><title>%s cluster=%s#%d</title></circle>",
                            x, y, color, eid, eid, e2.getKey(), e2.getValue().displayNumber()));
                }
            }
            sb.append(axes("log10(amplitude [uV])", "channel projection"));
            return tail(sb);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public String autocorrelation(String countsCsv, String clusterKey) {
        String[] parts = countsCsv.split(",");
        StringBuilder sb = head("Autocorrelation " + clusterKey + " — bin 0.5 ms, range ±10 ms", 360, 200);
        long max = 1;
        long[] vals = new long[parts.length];
        for (int i = 0; i < parts.length; i++) { vals[i] = Long.parseLong(parts[i]); max = Math.max(max, vals[i]); }
        for (int i = 0; i < vals.length; i++) {
            double x = 30 + i * 300.0 / vals.length;
            double h = vals[i] * 150.0 / max;
            sb.append(String.format("<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='#6366f1'/>",
                    x, 170 - h, 300.0 / vals.length, h));
        }
        sb.append(axes("lag [ms]", "count"));
        return tail(sb);
    }

    public String rate(String ratesCsv, String clusterKey) {
        String[] parts = ratesCsv.split(",");
        StringBuilder sb = head("Firing rate " + clusterKey + " [Hz] — 1 s bins", 360, 200);
        double max = 1;
        double[] vals = new double[parts.length];
        for (int i = 0; i < parts.length; i++) { vals[i] = Double.parseDouble(parts[i]); max = Math.max(max, vals[i]); }
        for (int i = 0; i < vals.length; i++) {
            double x = 30 + i * 300.0 / vals.length;
            double h = vals[i] / max * 150;
            sb.append(String.format("<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='#0ea5e9'/>",
                    x, 170 - h, 300.0 / Math.max(1, vals.length), h));
        }
        sb.append(axes("time since t0 [s]", "rate [Hz]"));
        return tail(sb);
    }

    /** 漂移时间线：每条 AMPLITUDE_DRIFT 节点一个分段 + 事件散点（幅度 vs 时间）。 */
    public String driftTimeline() {
        try {
            StringBuilder sb = head("Amplitude drift timeline — unit uV, time [s] since t0", 720, 260);
            var events = store.material().events();
            var clusters = store.labels().clusters(store.decision().meta("active_label_set"));
            var byId = new java.util.HashMap<String, Material.SpikeEvent>();
            events.forEach(e -> byId.put(e.eventId(), e));
            String[] palette = {"#2563eb", "#16a34a", "#dc2626", "#9333ea", "#ea580c", "#0891b2"};
            int ci = 0;
            for (var e2 : clusters.entrySet()) {
                String color = palette[ci++ % palette.length];
                var pts = e2.getValue().eventIds().stream().map(byId::get)
                        .filter(java.util.Objects::nonNull).filter(Material.SpikeEvent::valid)
                        .filter(e -> e.amplitude().siValue() != null)
                        .sorted(java.util.Comparator.comparingDouble(Material.SpikeEvent::timeSeconds))
                        .toList();
                if (pts.isEmpty()) continue;
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < pts.size(); i++) {
                    Material.SpikeEvent ev = pts.get(i);
                    double x = 40 + (ev.timeSeconds() - 10) / 11 * 650;
                    double y = 210 - (Math.abs(ev.amplitude().siValue()) - 40) / 90 * 170;
                    line.append(i == 0 ? "M" : "L").append(String.format("%.1f,%.1f ", x, y));
                }
                sb.append("<path d='").append(line).append("' fill='none' stroke='").append(color)
                        .append("' stroke-width='1.4'/>");
                sb.append(String.format("<text x='%d' y='%d' class='note' fill='%s'>%s (#%d)</text>",
                        700, 30 + ci * 14, color, e2.getKey(), e2.getValue().displayNumber()));
            }
            sb.append(axes("time [s]", "|amplitude| [uV]"));
            return tail(sb);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String errorSvg(String msg) {
        return "<svg xmlns='http://www.w3.org/2000/svg' width='300' height='80'>"
                + "<rect width='300' height='80' fill='#fee2e2'/>"
                + "<text x='10' y='40' class='note'>" + msg + "</text></svg>";
    }

    private StringBuilder head(String title, int w, int h) {
        return new StringBuilder(String.format(
                "<svg xmlns='http://www.w3.org/2000/svg' width='%d' height='%d' viewBox='0 0 %d %d' font-family='sans-serif'>"
                        + "<style>.lab{font-size:11px;fill:#111}.note{font-size:10px;fill:#444}</style>"
                        + "<text x='10' y='14' class='lab'>%s</text>", w, h, w, h, title));
    }
    private String axes(String xLab, String yLab) {
        return "<text x='150' y='197' class='note'>" + xLab + "</text>"
                + "<text x='6' y='105' class='note' transform='rotate(-90 10,105)'>" + yLab + "</text>";
    }
    private String tail(StringBuilder sb) { return sb.append("</svg>").toString(); }
}
