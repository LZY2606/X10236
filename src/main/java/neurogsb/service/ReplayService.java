package neurogsb.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import neurogsb.db.Database;
import neurogsb.model.Records.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 回放：回到任意旧事件时，恢复当时的依赖版本与可见结论，不用最新结果覆盖历史。 */
public final class ReplayService {
    private final Database db;
    private final Store store;

    public ReplayService(Database db, Store store) { this.db = db; this.store = store; }

    /** seq 为 null 表示最新状态。 */
    public ObjectNode stateAt(Long seq) {
        ObjectNode out = Store.M.createObjectNode();
        long effectiveSeq = seq != null ? seq : store.events(null).stream().mapToLong(Event::seq).max().orElse(0);
        out.put("seq", effectiveSeq);
        out.put("latest_seq", store.events(null).stream().mapToLong(Event::seq).max().orElse(0));

        // 单元谱系：cluster_label 材料 + renumber/split 事件（≤ seq）
        Map<String, ObjectNode> units = new LinkedHashMap<>();
        for (var m : store.materials("cluster_label")) {
            if (m.seq() > effectiveSeq) continue;
            String unitId = m.payload().get("unit_id").asText();
            ObjectNode u = units.computeIfAbsent(unitId, k -> {
                ObjectNode n = Store.M.createObjectNode();
                n.put("unit_id", k);
                return n;
            });
            u.put("cluster_no", m.payload().get("cluster_no").asInt());
            u.put("label_version", m.version());
        }
        for (var e : store.events(effectiveSeq)) {
            if (e.kind().equals("split")) {
                ObjectNode parent = units.get(e.payload().get("parent_unit").asText());
                if (parent != null) parent.put("status", "superseded");
            }
        }
        ArrayNode unitsArr = out.putArray("units");
        for (var u : units.values()) {
            String unitId = u.get("unit_id").asText();
            u.put("spike_count", store.spikes(unitId).size());
            // 可见派生结果：event_seq<=seq 且在该时点未被失效
            var invalidatedAt = new java.util.HashMap<String, Long>();
            for (var inv : store.invalidations(effectiveSeq)) invalidatedAt.put(inv.derivedId(), inv.eventSeq());
            ArrayNode derived = u.putArray("derived");
            for (var d : store.derivedFor(unitId, effectiveSeq)) {
                ObjectNode dn = derived.addObject();
                dn.put("id", d.id());
                dn.put("kind", d.kind());
                dn.put("status", invalidatedAt.containsKey(d.id()) ? "invalidated" : d.status());
                dn.put("param_version", d.paramVersion());
                dn.put("input_digest", d.inputDigest());
                dn.set("payload", d.payload());
            }
            unitsArr.add(u);
        }

        ArrayNode events = out.putArray("events");
        for (var e : store.events(effectiveSeq)) {
            ObjectNode en = events.addObject();
            en.put("id", e.id()); en.put("seq", e.seq()); en.put("kind", e.kind());
            en.put("actor", e.actor()); en.put("branch", e.branch());
            en.set("payload", e.payload());
            ArrayNode par = en.putArray("parents");
            e.parents().forEach(par::add);
        }

        ArrayNode diags = out.putArray("diagnostics");
        for (var d : store.diagnostics()) {
            ObjectNode dn = diags.addObject();
            dn.put("severity", d.severity()); dn.put("position", d.position());
            dn.put("message", d.message());
            if (d.rawValue() != null) dn.put("raw_value", d.rawValue());
        }

        ArrayNode invs = out.putArray("invalidations");
        for (var inv : store.invalidations(effectiveSeq)) {
            ObjectNode n = invs.addObject();
            n.put("derived_id", inv.derivedId()); n.put("reason", inv.reason());
            n.put("scope", inv.scope()); n.put("event_seq", inv.eventSeq());
        }

        ArrayNode cands = out.putArray("candidates");
        for (var c : store.allCandidates()) {
            ObjectNode n = cands.addObject();
            n.put("id", c.id()); n.put("subject_id", c.subjectId());
            n.put("kind", c.kind()); n.put("status", c.status());
            n.set("payload", c.payload());
        }

        ObjectNode bases = out.putObject("bases");
        bases.put("time", "ms since recording start (session 2026-09-19T00:33:12+09:00)");
        bases.put("amplitude", "µV");
        bases.put("geometry", "µm, probe-frame (x=lateral, y=depth)");
        var w = store.kvGet("window");
        w.ifPresent(s -> bases.set("window", Store.json(s)));
        return out;
    }
}
