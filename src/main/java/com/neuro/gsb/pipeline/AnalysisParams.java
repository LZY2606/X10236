package com.neuro.gsb.pipeline;

/** Parameter version for the derived analysis layer. Window bounds are ms on RECORDING_CLOCK. */
public record AnalysisParams(double refractoryMs, double binMs, Double windowStartMs, Double windowEndMs,
                             String version) {

    public static AnalysisParams initial() {
        return new AnalysisParams(1.5, 10.0, null, null, "analysis-v1");
    }

    public AnalysisParams withWindow(Double start, Double end, String newVersion) {
        return new AnalysisParams(refractoryMs, binMs, start, end, newVersion);
    }

    public String windowTag() {
        return (windowStartMs == null ? "-inf" : windowStartMs) + ".." + (windowEndMs == null ? "+inf" : windowEndMs);
    }

    public String paramVersion() {
        return version + "|refr=" + refractoryMs + "|bin=" + binMs + "|win=" + windowTag();
    }
}
