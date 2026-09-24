package neuro.spike.model;

/**
 * Half-open analysis interval [startSec, endSec) on the session time basis.
 * Half-open semantics make touching windows share exactly one endpoint event
 * rather than double counting it - the "time-window endpoint" case.
 */
public record AnalysisWindow(String windowId, String label, double startSec, double endSec) {
    public AnalysisWindow {
        if (!(endSec > startSec)) throw new IllegalArgumentException("window must have positive length");
    }
    public boolean contains(double t) { return t >= startSec && t < endSec; }
    /** Endpoint events at exactly endSec belong to the next window, not this one. */
    public boolean intersectsRange(double a, double b) { return a < endSec && b > startSec; }
}
