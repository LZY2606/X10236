package ephys;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Derived state obtained purely by replaying the event log up to a sequence
 *  number. Returning to an old event therefore restores the dependency
 *  versions and the visible conclusions of that moment. */
public final class State {

    public static final class Unit {
        public final String uid;
        public final Set<String> members = new LinkedHashSet<>();
        public String status = "active";
        public String origin;
        public long bornSeq;
        public Long retiredSeq;
        public String label;

        Unit(String uid, String origin, long bornSeq) {
            this.uid = uid;
            this.origin = origin;
            this.bornSeq = bornSeq;
        }
    }

    public final Map<String, Unit> units = new LinkedHashMap<>();
    public final List<JsonNode> evidence = new ArrayList<>();
    public final List<JsonNode> invalidations = new ArrayList<>();
    public final List<JsonNode> conflicts = new ArrayList<>();
    public final List<JsonNode> candidates = new ArrayList<>();
    public final Map<String, String> clusterToUnit = new LinkedHashMap<>();
    public long headSeq;
    public String featureVersion;

    public Unit unit(String uid) {
        return units.get(uid);
    }

    public List<Unit> activeUnits() {
        List<Unit> out = new ArrayList<>();
        for (Unit u : units.values()) if ("active".equals(u.status)) out.add(u);
        return out;
    }

    public Set<String> evidenceIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode e : evidence) ids.add(e.get("id").asText());
        return ids;
    }

    static void applyCluster(State st, JsonNode p, long seq) {
        st.featureVersion = p.get("param_version").asText();
        for (JsonNode r : p.withArray("retired")) {
            Unit u = st.units.get(r.get("uid").asText());
            if (u != null && !"rejected".equals(u.status)) {
                u.status = "retired";
                u.retiredSeq = seq;
            }
        }
        for (JsonNode c : p.withArray("clusters")) {
            String uid = c.get("uid").asText();
            String continued = c.hasNonNull("continued_from") ? c.get("continued_from").asText() : null;
            Unit u = st.units.get(uid);
            if (u == null) {
                u = new Unit(uid, continued == null ? "cluster" : "cluster-renamed", seq);
                st.units.put(uid, u);
            }
            u.members.clear();
            for (JsonNode m : c.withArray("members")) u.members.add(m.asText());
            u.label = c.get("label").asText();
            st.clusterToUnit.put(u.label, uid);
        }
    }

    static void applyAdjudication(State st, JsonNode p, long seq) {
        if (p.hasNonNull("conflict")) {
            st.conflicts.add(p);
        }
        for (JsonNode c : p.withArray("candidates")) {
            if (!c.get("adopted").asBoolean()) st.candidates.add(c);
        }
        JsonNode r = p.get("result");
        if (r == null || r.isNull()) return;
        switch (r.get("op").asText()) {
            case "merge" -> {
                Unit u = new Unit(r.get("new_uid").asText(), "merge", seq);
                for (JsonNode m : r.withArray("members")) u.members.add(m.asText());
                u.label = r.hasNonNull("label") ? r.get("label").asText() : null;
                st.units.put(u.uid, u);
                for (JsonNode t : r.withArray("retired")) retire(st, t.asText(), seq);
            }
            case "split" -> {
                for (JsonNode ch : r.withArray("children")) {
                    Unit u = new Unit(ch.get("uid").asText(), "split", seq);
                    for (JsonNode m : ch.withArray("members")) u.members.add(m.asText());
                    st.units.put(u.uid, u);
                }
                retire(st, r.get("parent").asText(), seq);
            }
            case "reject" -> {
                Unit u = st.units.get(r.get("uid").asText());
                if (u != null) u.status = "rejected";
            }
            case "accept" -> {
                // review confirmation; recorded in the log, no membership change
            }
            case "resolve" -> {
                // resolution is recorded as an event; conflicts remain visible in history
            }
            default -> throw new IllegalStateException("unknown adjudication op " + r);
        }
    }

    private static void retire(State st, String uid, long seq) {
        Unit u = st.units.get(uid);
        if (u != null) {
            u.status = "retired";
            u.retiredSeq = seq;
        }
    }

    public static State replay(List<JsonNode> events, long uptoSeq) {
        State st = new State();
        for (JsonNode ev : events) {
            long seq = ev.get("seq").asLong();
            if (seq > uptoSeq) break;
            st.headSeq = seq;
            String kind = ev.get("kind").asText();
            JsonNode p = ev.get("payload");
            switch (kind) {
                case "cluster" -> applyCluster(st, p, seq);
                case "invalidate" -> st.invalidations.add(p);
                case "evidence" -> {
                    for (JsonNode item : p.withArray("items")) st.evidence.add(item);
                }
                case "adjudicate" -> applyAdjudication(st, p, seq);
                case "import", "param_revision" -> {
                    // materials and spikes are loaded from their own tables
                }
                default -> throw new IllegalStateException("unknown event kind " + kind);
            }
        }
        return st;
    }
}
