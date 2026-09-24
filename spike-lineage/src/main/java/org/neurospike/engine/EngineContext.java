package org.neurospike.engine;

/** 派生引擎的参数版本上下文（paramVersion）。内容相同 -> nodeId 相同。 */
public record EngineContext(String labelSetId, int thresholdVersion,
                           double windowStart, double windowEnd,
                           double concurrencyTolSeconds, double refractorySeconds,
                           String sourcePatchKey) {
    public String canonical() {
        return String.join("|", labelSetId, String.valueOf(thresholdVersion),
                d(windowStart), d(windowEnd), d(concurrencyTolSeconds),
                d(refractorySeconds), sourcePatchKey == null ? "" : sourcePatchKey);
    }

    private static String d(double v) {
        if (v == Double.POSITIVE_INFINITY || v >= 1e11) return "inf";
        return String.format(java.util.Locale.ROOT, "%.6f", v);
    }
}
