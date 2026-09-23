package gsb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decision layer. Every accept/reject/split/merge/parameter change is an event
 * with explicit parents. Conflicting decisions with the same parents are kept
 * together with all legal candidates until a RESOLVE event picks one. Cluster
 * numbers are labels; renumbering only adds label rows pointing at the same
 * stable unit ids.
 */
public final class Adjudicator {
    public enum Outcome { OK, CONFLICT, SEMANTIC_UNSOLVABLE }

    public static final class Result {
        public final Outcome outcome;
        public final String eventId;
        public final List<String> affectedUnits;
        public final List<String> candidates; // legal candidates when undecidable
        public final String message;

        Result(Outcome o, String id, List<String> affected, List<String> candidates, String msg) {
            outcome = o; eventId = id; affectedUnits = affected; this.candidates = candidates; message = msg;
        }
    }

    private final Database db;
    private final Store store;
    private final Deriver deriver;

    public Adjudicator(Database db, Store store, Deriver deriver) {
        this.db = db;
        this.store = store;
        this.deriver = deriver;
    }

    public List<JsonNode> events(long asOfSeq) {
        return db.query("SELECT * FROM adjudication_events WHERE seq<=? ORDER BY seq",
                rs -> {
                    ObjectNode n = Json.obj();
                    n.put("id", rs.getString("id"));
                    n.put("seq", rs.getLong("seq"));
                    n.put("type", rs.getString("type"));
                    n.set("payload", Json.parse(rs.getString("payload_json")));
                    n.set("parents", Json.parse(rs.getString("parents_json")));
                    n.put("status", rs.getString("status"));
                    n.put("digest", rs.getString("digest"));
                    return n;
                }, asOfSeq);
    }

    public String recordEvent(String type, ObjectNode payload, List<String> parents, String status) {
        String id = db.newId("A");
        long seq = db.nextSeq();
        ObjectNode canonical = Json.obj();
        canonical.put("type", type);
        canonical.set("payload", payload);
        ArrayNode p = canonical.putArray("parents");
        parents.stream().sorted().forEach(p::add);
        db.execute("INSERT INTO adjudication_events(id,seq,type,payload_json,parents_json,status,digest)"
                        + " VALUES(?,?,?,?,?,?,?)",
                id, seq, type, Json.write(payload), Json.write(p), status, Json.digestOf(canonical));
        return id;
    }


    /** Carry labels of unaffected units forward into a new label set version. */
    private void carryForward(int newVersion, java.util.Set<String> excludeUnitIds) {
        int oldVersion = newVersion - 1;
        for (JsonNode l : store.labelsOfVersion(oldVersion)) {
            if (excludeUnitIds.contains(l.get("unitId").asText())) continue;
            ObjectNode nl = Json.obj();
            nl.put("id", db.newId("L"));
            nl.put("labelSetVersion", newVersion);
            nl.put("clusterNo", l.get("clusterNo").asInt());
            nl.put("unitId", l.get("unitId").asText());
            nl.set("spikeIds", l.get("spikeIds"));
            store.addClusterLabel(nl);
        }
    }

    /** Detect an open conflicting event with identical parents but different decision. */
    private JsonNode findConflict(String type, List<String> parents) {
        List<String> sortedParents = parents.stream().sorted().toList();
        for (JsonNode e : events(db.currentSeq())) {
            if (!e.get("status").asText().equals("APPLIED")) continue;
            if (!decisionTypeOf(e.get("type").asText()).equals(decisionTypeOf(type))) continue;
            List<String> ep = new ArrayList<>();
            e.get("parents").forEach(x -> ep.add(x.asText()));
            ep.sort(Comparator.naturalOrder());
            if (ep.equals(sortedParents) && !e.get("type").asText().equals(type)) return e;
        }
        return null;
    }

    private static String decisionTypeOf(String type) {
        return switch (type) {
            case "ACCEPT", "REJECT" -> "VERDICT";
            default -> type;
        };
    }

    /** Accept or reject a unit. Contradictory verdicts on the same parents conflict. */
    public Result verdict(String unitId, boolean accept, String rationale) {
        if (store.units().stream().noneMatch(u -> u.get("id").asText().equals(unitId))) {
            return new Result(Outcome.SEMANTIC_UNSOLVABLE, null, List.of(), List.of(),
                    "unknown unit " + unitId);
        }
        List<String> parents = new ArrayList<>();
        for (JsonNode l : store.currentLabels(db.currentSeq())) {
            if (l.get("unitId").asText().equals(unitId)) parents.add(l.get("id").asText());
        }
        String type = accept ? "ACCEPT" : "REJECT";
        JsonNode conflict = findConflict(type, parents);
        ObjectNode payload = Json.obj();
        payload.put("unitId", unitId);
        payload.put("rationale", rationale == null ? "" : rationale);
        if (conflict != null) {
            // keep both decisions and every legal candidate; nothing is overwritten
            ArrayNode candidates = payload.putArray("candidates");
            candidates.add(conflict.get("type").asText());
            candidates.add(type);
            String id = recordEvent(type, payload, parents, "CONFLICTED");
            db.execute("UPDATE adjudication_events SET status='CONFLICTED' WHERE id=?",
                    conflict.get("id").asText());
            List<String> cands = List.of(conflict.get("type").asText(), type);
            return new Result(Outcome.CONFLICT, id, List.of(unitId), cands,
                    "conflicts with " + conflict.get("id").asText());
        }
        String id = recordEvent(type, payload, parents, "APPLIED");
        store.setUnitStatus(unitId, accept ? "ACCEPTED" : "REJECTED");
        return new Result(Outcome.OK, id, List.of(unitId), List.of(), "ok");
    }

    /** Resolve a conflict by choosing one of the recorded candidates. */
    public Result resolve(String conflictEventId, String chosenType) {
        List<JsonNode> all = events(db.currentSeq());
        JsonNode target = all.stream().filter(e -> e.get("id").asText().equals(conflictEventId))
                .findFirst().orElse(null);
        if (target == null || !target.get("status").asText().equals("CONFLICTED")) {
            return new Result(Outcome.SEMANTIC_UNSOLVABLE, null, List.of(), List.of(),
                    "no open conflict " + conflictEventId);
        }
        List<String> candidates = new ArrayList<>();
        target.get("payload").withArray("candidates").forEach(c -> candidates.add(c.asText()));
        if (!candidates.contains(chosenType)) {
            return new Result(Outcome.SEMANTIC_UNSOLVABLE, null, List.of(), candidates,
                    "chosen value not among legal candidates");
        }
        String unitId = target.get("payload").get("unitId").asText();
        List<String> parents = new ArrayList<>();
        target.get("parents").forEach(p -> parents.add(p.asText()));
        parents.add(conflictEventId);
        ObjectNode payload = Json.obj();
        payload.put("unitId", unitId);
        payload.put("chosen", chosenType);
        payload.putArray("rejected").add(candidates.stream().filter(c -> !c.equals(chosenType)).findFirst().orElse(""));
        String id = recordEvent("RESOLVE", payload, parents, "APPLIED");
        // close every conflicting event on the same parents
        for (JsonNode e : all) {
            if (e.get("status").asText().equals("CONFLICTED")
                    && e.get("payload").path("unitId").asText().equals(unitId)) {
                db.execute("UPDATE adjudication_events SET status='RESOLVED' WHERE id=?",
                        e.get("id").asText());
            }
        }
        store.setUnitStatus(unitId, chosenType.equals("ACCEPT") ? "ACCEPTED" : "REJECTED");
        return new Result(Outcome.OK, id, List.of(unitId), List.of(), "ok");
    }

    /**
     * Split a unit into children. Spike ids may be shared between children
     * (shared events after split); provenance links every child to the parent
     * unit and the exact spike ids it received.
     */
    public Result split(String unitId, List<List<String>> childSpikeIds) {
        if (childSpikeIds.size() < 2) {
            return new Result(Outcome.SEMANTIC_UNSOLVABLE, null, List.of(), List.of(),
                    "split needs at least two children");
        }
        List<String> known = new ArrayList<>();
        store.spikesForUnit(unitId, db.currentSeq()).forEach(s -> known.add(s.get("id").asText()));
        for (List<String> child : childSpikeIds) {
            for (String sid : child) {
                if (!known.contains(sid)) {
                    return new Result(Outcome.SEMANTIC_UNSOLVABLE, null, List.of(), List.of(),
                            "spike " + sid + " does not belong to " + unitId);
                }
            }
        }
        List<String> parents = new ArrayList<>();
        for (JsonNode l : store.currentLabels(db.currentSeq())) {
            if (l.get("unitId").asText().equals(unitId)) parents.add(l.get("id").asText());
        }
        int version = store.latestLabelVersion(db.currentSeq()) + 1;
        List<String> affected = new ArrayList<>();
        affected.add(unitId);
        List<String> childUnits = new ArrayList<>();
        for (List<String> child : childSpikeIds) {
            String childUnit = store.addUnit("split:" + unitId);
            childUnits.add(childUnit);
            affected.add(childUnit);
            ObjectNode label = Json.obj();
            label.put("id", db.newId("L"));
            label.put("labelSetVersion", version);
            label.put("clusterNo", childUnits.size()); // fresh cluster numbers, identity stays in unitId
            label.put("unitId", childUnit);
            ArrayNode ids = label.putArray("spikeIds");
            child.stream().sorted().forEach(ids::add);
            store.addClusterLabel(label);
        }
        store.setUnitStatus(unitId, "SPLIT");
        deriver.invalidateDerived(unitId, "SPLIT");
        carryForward(version, java.util.Set.of(unitId));
        ObjectNode payload = Json.obj();
        payload.put("unitId", unitId);
        ArrayNode children = payload.putArray("children");
        for (int i = 0; i < childUnits.size(); i++) {
            ObjectNode c = children.addObject();
            c.put("unitId", childUnits.get(i));
            ArrayNode ids = c.putArray("spikeIds");
            childSpikeIds.get(i).stream().sorted().forEach(ids::add);
        }
        String id = recordEvent("SPLIT", payload, parents, "APPLIED");
        return new Result(Outcome.OK, id, affected, List.of(), "ok");
    }

    /** Merge units into a new unit; parents keep their identity and history. */
    public Result merge(List<String> unitIds) {
        if (unitIds.size() < 2) {
            return new Result(Outcome.SEMANTIC_UNSOLVABLE, null, List.of(), List.of(),
                    "merge needs at least two units");
        }
        List<String> existing = new ArrayList<>();
        store.units().forEach(u -> existing.add(u.get("id").asText()));
        for (String u : unitIds) {
            if (!existing.contains(u)) {
                return new Result(Outcome.SEMANTIC_UNSOLVABLE, null, List.of(), List.of(),
                        "unknown unit " + u);
            }
        }
        List<String> parents = new ArrayList<>();
        List<String> spikes = new ArrayList<>();
        for (String u : unitIds) {
            for (JsonNode l : store.currentLabels(db.currentSeq())) {
                if (l.get("unitId").asText().equals(u)) parents.add(l.get("id").asText());
            }
            store.spikesForUnit(u, db.currentSeq()).forEach(s -> spikes.add(s.get("id").asText()));
            store.setUnitStatus(u, "MERGED");
            deriver.invalidateDerived(u, "MERGED");
        }
        String merged = store.addUnit("merge:" + String.join("+", unitIds));
        int version = store.latestLabelVersion(db.currentSeq()) + 1;
        ObjectNode label = Json.obj();
        label.put("id", db.newId("L"));
        label.put("labelSetVersion", version);
        label.put("clusterNo", 1);
        label.put("unitId", merged);
        ArrayNode ids = label.putArray("spikeIds");
        spikes.stream().sorted().forEach(ids::add);
        store.addClusterLabel(label);
        carryForward(version, new java.util.LinkedHashSet<>(unitIds));
        ObjectNode payload = Json.obj();
        payload.put("mergedUnitId", merged);
        ArrayNode src = payload.putArray("sources");
        unitIds.forEach(src::add);
        String id = recordEvent("MERGE", payload, parents, "APPLIED");
        List<String> affected = new ArrayList<>(unitIds);
        affected.add(merged);
        return new Result(Outcome.OK, id, affected, List.of(), "ok");
    }

    /**
     * Cluster renumbering: new label rows, same unit ids. Proof that cluster
     * numbers are labels, not identities.
     */
    public Result renumber(java.util.Map<Integer, Integer> mapping) {
        int oldVersion = store.latestLabelVersion(db.currentSeq());
        int newVersion = oldVersion + 1;
        List<String> parents = new ArrayList<>();
        List<String> affected = new ArrayList<>();
        for (JsonNode l : store.currentLabels(db.currentSeq())) {
            Integer newNo = mapping.get(l.get("clusterNo").asInt());
            if (newNo == null) continue;
            parents.add(l.get("id").asText());
            ObjectNode nl = Json.obj();
            nl.put("id", db.newId("L"));
            nl.put("labelSetVersion", newVersion);
            nl.put("clusterNo", newNo);
            nl.put("unitId", l.get("unitId").asText());
            nl.set("spikeIds", l.get("spikeIds"));
            store.addClusterLabel(nl);
            affected.add(l.get("unitId").asText());
        }
        if (parents.isEmpty()) {
            return new Result(Outcome.SEMANTIC_UNSOLVABLE, null, List.of(), List.of(),
                    "mapping does not touch any current cluster");
        }
        java.util.Set<String> mappedUnits = new java.util.LinkedHashSet<>(affected);
        int carryVersion = newVersion;
        for (JsonNode l : store.labelsOfVersion(oldVersion)) {
            if (mappedUnits.contains(l.get("unitId").asText())) continue;
            if (mapping.containsKey(l.get("clusterNo").asInt())) continue;
            ObjectNode nl = Json.obj();
            nl.put("id", db.newId("L"));
            nl.put("labelSetVersion", carryVersion);
            nl.put("clusterNo", l.get("clusterNo").asInt());
            nl.put("unitId", l.get("unitId").asText());
            nl.set("spikeIds", l.get("spikeIds"));
            store.addClusterLabel(nl);
        }
        ObjectNode payload = Json.obj();
        ObjectNode m = payload.putObject("mapping");
        mapping.forEach((k, v) -> m.put(String.valueOf(k), v));
        String id = recordEvent("RENUMBER", payload, parents, "APPLIED");
        affected.sort(Comparator.naturalOrder());
        return new Result(Outcome.OK, id, affected, List.of(), "ok");
    }
}
