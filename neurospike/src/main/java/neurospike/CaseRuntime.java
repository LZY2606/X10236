package neurospike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays a case pack: baseline import keeps the six layers separate, then the
 * domain script is applied in order (parameter revision, local source change,
 * conflicting decisions). Scripted decisions become exactly the same append-only
 * events as UI decisions.
 */
public final class CaseRuntime {

    public record ReplayResult(State state, List<AppService.Transition> transitions,
                               List<Diagnostic> warnings) {
    }

    public static Event baselineEvent(CasePack pack) {
        String digest = Canonical.shortHash(Canonical.of(Json.NODE.objectNode()
                .put("caseId", pack.caseId() == null ? "" : pack.caseId())
                .put("domain", pack.domain() == null ? "" : pack.domain())
                .put("layered", true)));
        String causal = Canonical.of(Json.NODE.objectNode()
                .put("seq", 1L).put("parent", "root").put("kind", Event.BASELINE_IMPORT)
                .put("inputDigest", digest).put("paramVersion", "feature@1")
                .put("payload", "baseline"));
        return new Event("evt_" + Canonical.shortHash(causal), 1L,
                Event.BASELINE_IMPORT, "root", Json.NODE.objectNode()
                .put("kind", "baseline-import").put("layers",
                        "waveform,probe,threshold,featureVersion,clusterLabel,adjudication"),
                digest, "feature@1", "OK", null, java.time.Instant.now().toString());
    }

    public static ReplayResult replay(CasePack pack, List<Diagnostic> warnings) {
        Event baseline = baselineEvent(pack);
        State state = State.baseline(pack, baseline);

        // baseline adjudication material becomes decisions but stays a separate layer
        List<AppService.Transition> transitions = new ArrayList<>();
        for (AdjudicationSeed seed : pack.adjudicationEvents()) {
            AppService svc = new AppService(state);
            long sample = seed.atSample() == null ? state.events().size()
                    : Long.parseLong(seed.atSample());
            AppService.Transition t = svc.decision(seed.targetId(),
                    seed.targetKind() == null ? "FINDING" : seed.targetKind(),
                    seed.verdict(), seed.rationale() == null ? "" : seed.rationale(),
                    seed.reviewer() == null ? "baseline" : seed.reviewer(),
                    seed.candidates(), null, null);
            state = t.state();
            transitions.add(t);
        }

        JsonNode script = pack.script();
        if (script != null && script.isArray()) {
            for (JsonNode op : script) {
                AppService svc = new AppService(state);
                AppService.Transition t = svc.applyScriptOp(op);
                state = t.state();
                transitions.add(t);
            }
        }
        return new ReplayResult(state, List.copyOf(transitions),
                warnings == null ? List.of() : List.copyOf(warnings));
    }
}
