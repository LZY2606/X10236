package org.neuro.spike.engine;

import org.neuro.spike.model.Diagnostic;
import org.neuro.spike.model.ResultStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic feature extraction. Version 1 maps an event's stable
 * attributes to a 2-D projection: x = time(s), y = amplitude(uV) with a
 * channel offset. The mapping is pure and identical on every replay.
 */
public final class FeatureExtractor {
    private FeatureExtractor() {
    }

    public record Point(String eventUid, double x, double y) {
    }

    public record Extraction(List<Point> points, List<Diagnostic> diagnostics, ResultStatus status) {
    }

    public static Extraction extract(List<DetEvent> events, int revision) {
        if (revision != 1) {
            return new Extraction(List.of(),
                    List.of(Diagnostic.of("feature.revision", "UNKNOWN_FEATURE_VERSION",
                            "no spec registered for feature revision " + revision)),
                    ResultStatus.INCOMPLETE);
        }
        List<Point> points = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (DetEvent e : events) {
            if (!e.usableForFeatures()) {
                String reason = e.corrupt() ? e.corruptionReason() : e.excludeReason();
                diagnostics.add(Diagnostic.of("feature.event[" + e.uid() + "]",
                        e.corrupt() ? "CORRUPT_WAVEFORM" : "EXCLUDED_BY_SEGMENT", reason));
                continue;
            }
            if (e.amplitude() == null || !e.amplitude().convertible()) {
                diagnostics.add(Diagnostic.of("feature.event[" + e.uid() + "].amplitude",
                        "UNCONVERTIBLE_AMPLITUDE",
                        e.amplitude() == null ? "amplitude missing" : e.amplitude().diagnostic()));
                continue;
            }
            double uv = e.amplitude().si().get() * 1e6;
            double x = e.timeSec();
            double y = uv + e.channel() * 2.0;
            points.add(new Point(e.uid(), round(x), round(y)));
        }
        ResultStatus status = points.isEmpty() && !diagnostics.isEmpty()
                ? ResultStatus.INCOMPLETE : ResultStatus.OK;
        return new Extraction(points, diagnostics, status);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
