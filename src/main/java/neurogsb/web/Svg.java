package neurogsb.web;

import com.fasterxml.jackson.databind.JsonNode;
import neurogsb.model.Records.Spike;

import java.util.List;
import java.util.Locale;

/** 服务端 SVG 渲染：每个面板标注单位、坐标/时间基准、有效区间与失效原因。 */
public final class Svg {
    private Svg() {}

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String num(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    static String header(int w, int h, String title, String axes) {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 " + w + " " + h
                + "' class='panel-svg' font-family='monospace'>"
                + "<rect width='" + w + "' height='" + h + "' fill='#10141c' rx='6'/>"
                + "<text x='10' y='16' fill='#8fd3ff' font-size='11'>" + esc(title) + "</text>"
                + "<text x='10' y='" + (h - 6) + "' fill='#7a8699' font-size='9'>" + esc(axes) + "</text>";
    }

    /** 波形片段：标准棘波形状按振幅缩放；点击设置联动时间。 */
    public static String waveform(List<Spike> spikes, double selT, int w, int h) {
        StringBuilder sb = new StringBuilder(header(w, h,
                "波形片段 waveform", "x: 样本序号 @30kHz · y: µV · 时间基准: ms since recording start"));
        double midY = h / 2.0 + 8;
        sb.append("<line x1='10' y1='").append(num(midY)).append("' x2='").append(w - 10)
          .append("' y2='").append(num(midY)).append("' stroke='#2a3346'/>");
        int n = Math.min(spikes.size(), 24);
        int drawn = 0;
        for (int i = 0; i < spikes.size() && drawn < n; i++) {
            Spike s = spikes.get(i);
            if (s.tMs() == null || s.ampUv() == null) continue;
            double x0 = 20 + drawn * (w - 40.0) / Math.max(1, n - 1);
            double scale = s.ampUv() / 120.0 * (h / 3.2);
            StringBuilder d = new StringBuilder();
            for (int k = 0; k <= 16; k++) {
                double x = (k - 5.6) / 1.9;
                double yv = -Math.exp(-x * x) + 0.15 * Math.exp(-Math.pow(x - 2.5, 2));
                d.append(k == 0 ? "M" : "L")
                 .append(num(x0 + k * 1.6)).append(" ").append(num(midY - yv * scale));
            }
            boolean sel = Math.abs(s.tMs() - selT) < 500;
            sb.append("<path d='").append(d).append("' fill='none' stroke='")
              .append(sel ? "#ffd166" : "#4ecdc4").append("' stroke-width='")
              .append(sel ? 2.2 : 1.2).append("' class='spike-wave' data-t='")
              .append(num(s.tMs())).append("'><title>").append(num(s.tMs()))
              .append(" ms · ").append(num(s.ampUv())).append(" µV</title></path>");
            drawn++;
        }
        if (drawn == 0) sb.append("<text x='20' y='").append(num(midY))
                .append("' fill='#7a8699' font-size='10'>无有效波形（见诊断）</text>");
        return sb.append("</svg>").toString();
    }

    /** 特征投影：f1/f2 散点。 */
    public static String projection(JsonNode projectionPayload, List<Spike> allUnitsSpikes,
                                    String unitId, int w, int h) {
        StringBuilder sb = new StringBuilder(header(w, h,
                "特征投影 feature projection", "x: f1 (µV 派生) · y: f2 · 特征版本见派生记录"));
        double maxF1 = 1, maxF2 = 1;
        for (Spike s : allUnitsSpikes) { maxF1 = Math.max(maxF1, Math.abs(s.f1())); maxF2 = Math.max(maxF2, Math.abs(s.f2())); }
        for (Spike s : allUnitsSpikes) {
            double x = 30 + (s.f1() / maxF1) * (w - 60);
            double y = h - 30 - (s.f2() / maxF2) * (h - 60);
            boolean own = s.unitId().equals(unitId);
            sb.append("<circle cx='").append(num(x)).append("' cy='").append(num(y))
              .append("' r='").append(own ? 4 : 2.5).append("' fill='")
              .append(own ? "#ffd166" : "#3a4a63").append("' fill-opacity='0.85'>")
              .append("<title>").append(esc(s.unitId())).append(" · ").append(num(s.tMs() == null ? -1 : s.tMs()))
              .append(" ms</title></circle>");
        }
        return sb.append("</svg>").toString();
    }

    /** 自相关直方图。 */
    public static String acg(JsonNode acgPayload, int w, int h) {
        StringBuilder sb = new StringBuilder(header(w, h,
                "自相关 autocorrelogram", "x: 滞后 ms (bin=1ms) · y: 计数 · 不应期见证据事件"));
        if (acgPayload == null) return sb.append("</svg>").toString();
        JsonNode bins = acgPayload.get("bins");
        int max = 1;
        for (JsonNode b : bins) max = Math.max(max, b.asInt());
        double bw = (w - 40.0) / bins.size();
        for (int i = 0; i < bins.size(); i++) {
            double bh = (bins.get(i).asDouble() / max) * (h - 60);
            sb.append("<rect x='").append(num(20 + i * bw)).append("' y='").append(num(h - 30 - bh))
              .append("' width='").append(num(Math.max(1, bw - 1))).append("' height='").append(num(bh))
              .append("' fill='#7b9fef'/><title>").append(i).append(" ms: ")
              .append(bins.get(i).asInt()).append("</title></rect>");
        }
        return sb.append("</svg>").toString();
    }

    /** 发放率时间曲线。 */
    public static String rate(JsonNode ratePayload, double selT, int w, int h) {
        StringBuilder sb = new StringBuilder(header(w, h,
                "发放率 firing rate", "x: 时间 ms · y: spikes/s (bin=1000ms)"));
        if (ratePayload == null) return sb.append("</svg>").toString();
        JsonNode bins = ratePayload.get("bins");
        double t0 = ratePayload.get("t0_ms").asDouble();
        int max = 1;
        for (JsonNode b : bins) max = Math.max(max, b.asInt());
        double bw = (w - 40.0) / bins.size();
        for (int i = 0; i < bins.size(); i++) {
            double bh = (bins.get(i).asDouble() / max) * (h - 60);
            sb.append("<rect x='").append(num(20 + i * bw)).append("' y='").append(num(h - 30 - bh))
              .append("' width='").append(num(Math.max(1, bw - 1))).append("' height='").append(num(bh))
              .append("' fill='#6fd6a5' class='rate-bin' data-t='").append(num(t0 + i * 1000))
              .append("'><title>").append(num(t0 + i * 1000)).append(" ms: ")
              .append(bins.get(i).asInt()).append(" spikes/s</title></rect>");
        }
        if (selT >= t0) {
            double x = 20 + ((selT - t0) / 1000.0) * bw;
            sb.append("<line x1='").append(num(x)).append("' y1='20' x2='").append(num(x))
              .append("' y2='").append(h - 30).append("' stroke='#ffd166' stroke-dasharray='3,2'/>");
        }
        return sb.append("</svg>").toString();
    }

    /** 漂移时间线：振幅随时间 + 漂移斜率 + 证据标记。 */
    public static String drift(List<Spike> spikes, JsonNode driftPayload, double selT, int w, int h) {
        StringBuilder sb = new StringBuilder(header(w, h,
                "漂移时间线 drift timeline", "x: 时间 ms · y: 振幅 µV · 漂移=证据非身份"));
        var pts = spikes.stream().filter(s -> s.tMs() != null && s.ampUv() != null).toList();
        if (pts.isEmpty()) return sb.append("</svg>").toString();
        double tMin = pts.get(0).tMs(), tMax = pts.get(pts.size() - 1).tMs();
        double aMax = pts.stream().mapToDouble(Spike::ampUv).max().orElse(1) * 1.1;
        for (Spike s : pts) {
            double x = 30 + (s.tMs() - tMin) / Math.max(1, tMax - tMin) * (w - 60);
            double y = h - 30 - (s.ampUv() / aMax) * (h - 60);
            boolean sel = Math.abs(s.tMs() - selT) < 500;
            sb.append("<circle cx='").append(num(x)).append("' cy='").append(num(y))
              .append("' r='").append(sel ? 5 : 3).append("' fill='")
              .append(sel ? "#ffd166" : "#ff8fa3").append("' class='drift-pt' data-t='")
              .append(num(s.tMs())).append("'><title>").append(num(s.tMs())).append(" ms · ")
              .append(num(s.ampUv())).append(" µV</title></circle>");
        }
        if (driftPayload != null && driftPayload.get("detected").asBoolean()) {
            double slope = driftPayload.get("slope_uv_per_ms").asDouble();
            double mean = driftPayload.get("mean_uv").asDouble();
            double y0 = mean + slope * (tMin - (tMin + tMax) / 2) * (tMax - tMin) / Math.max(1, tMax - tMin);
            double x1 = 30, x2 = w - 30;
            double ya = h - 30 - ((mean - slope * (tMax - tMin) / 2) / aMax) * (h - 60);
            double yb = h - 30 - ((mean + slope * (tMax - tMin) / 2) / aMax) * (h - 60);
            sb.append("<line x1='").append(num(x1)).append("' y1='").append(num(ya))
              .append("' x2='").append(num(x2)).append("' y2='").append(num(yb))
              .append("' stroke='#ff5d73' stroke-dasharray='5,3'/>")
              .append("<text x='").append(num(x2 - 160)).append("' y='24' fill='#ff5d73' font-size='9'>漂移斜率 ")
              .append(String.format(Locale.ROOT, "%.5f", slope)).append(" µV/ms</text>");
        }
        return sb.append("</svg>").toString();
    }
}
