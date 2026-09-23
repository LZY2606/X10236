package neuro.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import neuro.model.Diagnostic;
import neuro.model.Event;

/**
 * Mutable replay state. Built deterministically from the ordered event log; equal inputs produce an
 * equal ordering and equal derived digests. The store can rebuild this at any seq for time travel.
 */
public final class State {
    public final MaterialSet material = new MaterialSet();
    public final Map<String, Unit> units = new LinkedHashMap<>();
    public final List<Event> events = new ArrayList<>();
    public final Map<String, DerivedResult> results = new LinkedHashMap<>();
    public final List<Diagnostic> globalDiagnostics = new ArrayList<>();
    /** Scopes (e.g. a unit or spike id) whose local derivation failed. */
    public final Map<String, Diagnostic> localFailures = new LinkedHashMap<>();

    public AnalysisWindow window = AnalysisWindow.FULL;
    public String currentParamVersion = "param/v1";
    public long seq = 0;

    public Unit unitByLabel(String label) {
        for (Unit u : units.values()) if (u.label().equals(label)) return u;
        return null;
    }

    public List<String> unitIdsContaining(String spikeId) {
        List<String> ids = new ArrayList<>();
        for (Unit u : units.values()) if (u.hasSpike(spikeId)) ids.add(u.id());
        return ids;
    }
}
