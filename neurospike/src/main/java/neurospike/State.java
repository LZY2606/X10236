package neurospike;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable replay state at one point in the event log. The engine derives
 * everything it needs (membership, evidence, projections, affected sets) purely
 * from a {@code State}; nothing browser-side decides business rules.
 *
 * Material layers are versioned independently: baseline import creates v1 of
 * each layer, a PARAM_REVISION creates a new FeatureVersionSpec version, a
 * SOURCE_CHANGE creates new waveform/detection versions in a bounded scope.
 */
public record State(
        String caseId,
        String title,
        TimeBasis timeBasis,
        Probe probe,
        List<Threshold> thresholds,
        List<MaterialRev<WaveformRef>> waveformVersions,
        List<MaterialRev<Detection>> detectionVersions,
        List<MaterialRev<FeatureVersionSpec>> featureSpecVersions,
        List<MaterialRev<LabelSet>> labelSetVersions,
        List<AdjudicationSeed> baselineAdjudications,
        List<Event> events,
        List<Decision> decisions,
        List<LineageEdge> lineageEdges) {

    public State {
        waveformVersions = List.copyOf(waveformVersions);
        detectionVersions = List.copyOf(detectionVersions);
        featureSpecVersions = List.copyOf(featureSpecVersions);
        labelSetVersions = List.copyOf(labelSetVersions);
        baselineAdjudications = List.copyOf(baselineAdjudications);
        events = List.copyOf(events);
        decisions = List.copyOf(decisions);
        lineageEdges = List.copyOf(lineageEdges);
    }

    public static State baseline(CasePack pack, Event importEvent) {
        List<MaterialRev<WaveformRef>> wf = new ArrayList<>();
        for (WaveformRef w : pack.waveforms()) {
            wf.add(new MaterialRev<>(materialId(w.waveformId(), 1), w.waveformId(), 1,
                    importEvent.eventId(), Canonical.shortHash(
                            Canonical.of(w, Json.MAPPER)), w));
        }
        List<MaterialRev<Detection>> det = new ArrayList<>();
        for (Detection d : pack.detections()) {
            det.add(new MaterialRev<>(materialId(d.detectionId(), 1), d.detectionId(), 1,
                    importEvent.eventId(), Canonical.shortHash(
                            Canonical.of(d, Json.MAPPER)), d));
        }
        List<MaterialRev<FeatureVersionSpec>> fs = new ArrayList<>();
        fs.add(new MaterialRev<>("feature@1", "feature", 1, importEvent.eventId(),
                Canonical.shortHash(Canonical.of(pack.featureSpec(), Json.MAPPER)),
                pack.featureSpec()));
        // Only the first label set is live at baseline; later label sets become
        // separate LABEL_REVISION events during replay.
        List<MaterialRev<LabelSet>> ls = new ArrayList<>();
        if (!pack.labelSets().isEmpty()) {
            LabelSet first = pack.labelSets().get(0);
            ls.add(new MaterialRev<>("labels@1", "labels", 1, importEvent.eventId(),
                    Canonical.shortHash(Canonical.of(first, Json.MAPPER)), first));
        }
        return new State(pack.caseId(), pack.title(), pack.timeBasis(), pack.probe(),
                List.copyOf(pack.thresholds()), List.copyOf(wf), List.copyOf(det),
                List.copyOf(fs), List.copyOf(ls),
                List.copyOf(pack.adjudicationEvents()),
                List.of(importEvent), List.of(), List.of());
    }

    static String materialId(String key, int version) {
        return key + "@v" + version;
    }

    public FeatureVersionSpec currentSpec() {
        return featureSpecVersions.get(featureSpecVersions.size() - 1).material();
    }

    public LabelSet currentLabels() {
        return labelSetVersions.get(labelSetVersions.size() - 1).material();
    }

    public Event head() {
        return events.get(events.size() - 1);
    }

    /** All detection versions, newest first, keyed by detection id. */
    public java.util.Map<String, MaterialRev<Detection>> detectionAtOrBefore(long seq) {
        java.util.Map<String, MaterialRev<Detection>> out = new java.util.HashMap<>();
        for (MaterialRev<Detection> rev : detectionVersions) {
            long revSeq = seqOf(rev.introducedByEvent());
            if (revSeq <= seq) {
                out.put(rev.key(), rev);
            }
        }
        return out;
    }

    public java.util.Map<String, MaterialRev<WaveformRef>> waveformAtOrBefore(long seq) {
        java.util.Map<String, MaterialRev<WaveformRef>> out = new java.util.HashMap<>();
        for (MaterialRev<WaveformRef> rev : waveformVersions) {
            if (seqOf(rev.introducedByEvent()) <= seq) {
                out.put(rev.key(), rev);
            }
        }
        return out;
    }

    public MaterialRev<FeatureVersionSpec> specAtOrBefore(long seq) {
        MaterialRev<FeatureVersionSpec> result = featureSpecVersions.get(0);
        for (MaterialRev<FeatureVersionSpec> rev : featureSpecVersions) {
            if (seqOf(rev.introducedByEvent()) <= seq) {
                result = rev;
            }
        }
        return result;
    }

    public MaterialRev<LabelSet> labelsAtOrBefore(long seq) {
        MaterialRev<LabelSet> result = labelSetVersions.get(0);
        for (MaterialRev<LabelSet> rev : labelSetVersions) {
            if (seqOf(rev.introducedByEvent()) <= seq) {
                result = rev;
            }
        }
        return result;
    }

    private long seqOf(String eventId) {
        return events.stream().filter(e -> e.eventId().equals(eventId))
                .mapToLong(Event::seq).findFirst().orElseThrow();
    }

    public List<Decision> decisionsAtOrBefore(long seq) {
        List<Decision> out = new ArrayList<>();
        for (Decision d : decisions) {
            if (d.seq() <= seq) {
                out.add(d);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public List<LineageEdge> edgesAtOrBefore(long seq) {
        List<LineageEdge> out = new ArrayList<>();
        for (LineageEdge e : lineageEdges) {
            if (e.seq() <= seq) {
                out.add(e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public List<Event> eventsAtOrBefore(long seq) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.seq() <= seq) {
                out.add(e);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
