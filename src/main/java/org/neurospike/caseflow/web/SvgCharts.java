package org.neurospike.caseflow.web;

import org.neurospike.caseflow.domain.DomainRecords.FeaturePoint;
import org.neurospike.caseflow.domain.DomainRecords.WaveformFragment;

import java.util.List;
import java.util.Locale;

public final class SvgCharts {
    private SvgCharts() {
    }

    public static String waveform(String stableId, List<WaveformFragment> fragments,
                                  String amplitudeUnit, String timeBasis) {
        StringBuilder body = new StringBuilder();
        double maxAbs = fragments.stream()
                .flatMap(fragment -> fragment.samples().stream())
                .mapToDouble(Math::abs).max().orElse(1.0);
        if (maxAbs == 0) {
            maxAbs = 1;
        }
        int colorIndex = 0;
        String[] colors = {"#2563eb", "#dc2626", "#059669", "#7c3aed", "#ea580c"};
        for (WaveformFragment fragment : fragments) {
            StringBuilder points = new StringBuilder();
            int size = fragment.samples().size();
            for (int index = 0; index < size; index++) {
                double x = 30.0 + 420.0 * index / Math.max(1, size - 1);
                double y = 80.0 - 60.0 * fragment.samples().get(index) / maxAbs;
                points.append(fmt(x)).append(',').append(fmt(y)).append(' ');
            }
            String color = colors[colorIndex++ % colors.length];
            body.append("<polyline fill='none' stroke='").append(color)
                    .append("' stroke-width='1.5' points='").append(points).append("'>")
                    .append("<title>").append(esc(fragment.waveformId())).append(" / ")
                    .append(esc(fragment.detectionId())).append("</title></polyline>");
        }
        return svg(stableId, "waveform", body.toString(),
                "x: sample (" + timeBasis + ")", "y: amplitude (" + amplitudeUnit + ")");
    }

    public static String projection(String stableId, List<FeaturePoint> points, String coordinateBasis) {
        StringBuilder body = new StringBuilder();
        double max = points.stream().flatMap(point -> point.coordinates().stream())
                .mapToDouble(Math::abs).max().orElse(1.0);
        if (max == 0) {
            max = 1;
        }
        for (FeaturePoint point : points) {
            double x = 30.0 + 210.0 * (point.coordinates().get(0) / max + 1);
            double y = 140.0 - 110.0 * (point.coordinates().get(1) / max + 1) / 2.0;
            body.append("<circle cx='").append(fmt(x)).append("' cy='").append(fmt(y))
                    .append("' r='4' fill='#2563eb'><title>").append(esc(point.featurePointId()))
                    .append(" / ").append(esc(point.detectionId())).append("</title></circle>");
        }
        return svg(stableId, "projection", body.toString(),
                "x: PC1 (" + coordinateBasis + ")", "y: PC2 (" + coordinateBasis + ")");
    }

    public static String autocorrelation(String stableId, List<Long> sampleTimes, long sampleRateHz) {
        int bins = 25;
        double binMs = 2.0;
        int[] counts = new int[bins];
        for (int left = 0; left < sampleTimes.size(); left++) {
            for (int right = left + 1; right < sampleTimes.size(); right++) {
                double deltaMs = (sampleTimes.get(right) - sampleTimes.get(left)) * 1000.0 / sampleRateHz;
                int bin = (int) (deltaMs / binMs);
                if (bin >= 0 && bin < bins) {
                    counts[bin]++;
                }
            }
        }
        int max = 1;
        for (int count : counts) {
            max = Math.max(max, count);
        }
        StringBuilder body = new StringBuilder();
        for (int bin = 0; bin < bins; bin++) {
            double height = 110.0 * counts[bin] / max;
            double x = 30.0 + bin * 17.0;
            body.append("<rect x='").append(fmt(x)).append("' y='").append(fmt(140 - height))
                    .append("' width='14' height='").append(fmt(height)).append("' fill='#059669'>")
                    .append("<title>").append(fmt(bin * binMs)).append("-").append(fmt((bin + 1) * binMs))
                    .append(" ms: ").append(counts[bin]).append("</title></rect>");
        }
        return svg(stableId, "autocorrelation", body.toString(), "x: lag (ms)", "y: count (pairs)");
    }

    public static String firingRate(String stableId, List<Long> sampleTimes,
                                    long windowStart, long windowEnd, String timeBasis) {
        int bins = 20;
        long span = Math.max(1, windowEnd - windowStart);
        int[] counts = new int[bins];
        for (long time : sampleTimes) {
            int bin = (int) ((time - windowStart) * bins / span);
            if (bin >= 0 && bin < bins) {
                counts[bin]++;
            }
        }
        int max = 1;
        for (int count : counts) {
            max = Math.max(max, count);
        }
        StringBuilder body = new StringBuilder();
        for (int bin = 0; bin < bins; bin++) {
            double height = 110.0 * counts[bin] / max;
            double x = 30.0 + bin * 21.0;
            body.append("<rect x='").append(fmt(x)).append("' y='").append(fmt(140 - height))
                    .append("' width='18' height='").append(fmt(height)).append("' fill='#7c3aed'>")
                    .append("<title>bin ").append(bin).append(": ").append(counts[bin])
                    .append(" spikes</title></rect>");
        }
        return svg(stableId, "firing-rate", body.toString(),
                "x: sample (" + timeBasis + ")", "y: spikes/bin");
    }

    public static String driftTimeline(String stableId, List<Long> sampleTimes, List<Double> peaks,
                                       String amplitudeUnit, String timeBasis) {
        StringBuilder body = new StringBuilder();
        if (!sampleTimes.isEmpty()) {
            long minTime = sampleTimes.stream().mapToLong(Long::longValue).min().orElse(0);
            long maxTime = sampleTimes.stream().mapToLong(Long::longValue).max().orElse(1);
            double maxPeak = peaks.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
            if (maxPeak == 0) {
                maxPeak = 1;
            }
            StringBuilder points = new StringBuilder();
            for (int index = 0; index < sampleTimes.size(); index++) {
                double x = 30.0 + 420.0 * (sampleTimes.get(index) - minTime)
                        / Math.max(1, maxTime - minTime);
                double y = 140.0 - 110.0 * peaks.get(index) / maxPeak;
                points.append(fmt(x)).append(',').append(fmt(y)).append(' ');
                body.append("<circle cx='").append(fmt(x)).append("' cy='").append(fmt(y))
                        .append("' r='3.5' fill='#ea580c'><title>t=").append(sampleTimes.get(index))
                        .append(" peak=").append(fmt(peaks.get(index))).append(" ")
                        .append(esc(amplitudeUnit)).append("</title></circle>");
            }
            body.insert(0, "<polyline fill='none' stroke='#ea580c' stroke-width='1.5' points='"
                    + points + "'/>");
        }
        return svg(stableId, "drift-timeline", body.toString(),
                "x: sample (" + timeBasis + ")", "y: peak (" + amplitudeUnit + ")");
    }

    private static String svg(String stableId, String kind, String body, String xLabel, String yLabel) {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 480 180' width='100%' height='180' "
                + "data-stable-id='" + esc(stableId) + "' data-panel='" + esc(kind) + "' role='img'>"
                + "<rect x='0' y='0' width='480' height='180' fill='#ffffff' stroke='#cbd5e1'/>"
                + "<line x1='30' y1='140' x2='460' y2='140' stroke='#94a3b8'/>"
                + "<line x1='30' y1='20' x2='30' y2='140' stroke='#94a3b8'/>"
                + body
                + "<text x='240' y='165' text-anchor='middle' font-size='10' fill='#475569'>"
                + esc(xLabel) + "</text>"
                + "<text x='12' y='80' text-anchor='middle' font-size='10' fill='#475569' "
                + "transform='rotate(-90 12 80)'>" + esc(yLabel) + "</text>"
                + "</svg>";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
