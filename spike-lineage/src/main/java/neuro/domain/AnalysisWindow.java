package neuro.domain;

/**
 * Half-open analysis time window [startS, endS). Endpoint semantics are first-class: a spike exactly
 * at {@code endS} is outside, a spike exactly at {@code startS} is inside.
 */
public record AnalysisWindow(double startS, double endS) {
    public static final AnalysisWindow FULL = new AnalysisWindow(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

    public boolean contains(double t) {
        return t >= startS && t < endS;
    }

    /** True if a unit with any spike times in [lo,hi] intersects this window. */
    public boolean intersects(double lo, double hi) {
        return lo < endS && hi >= startS;
    }
}
