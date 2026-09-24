package neurogsb.service;

import com.fasterxml.jackson.databind.JsonNode;
import neurogsb.db.Database;
import neurogsb.model.OpResult;
import neurogsb.model.Quantity;
import neurogsb.model.Records.*;
import neurogsb.model.Records.Diagnostic;

/** 导入：保留原始表示，派生层才规范化；无法转换的值留在原位置并附诊断。 */
public final class IngestService {
    private final Database db;
    private final Store store;

    public IngestService(Database db, Store store) { this.db = db; this.store = store; }

    public OpResult importPack(JsonNode pack) {
        if (pack == null || !pack.has("materials") || !pack.has("spikes")) {
            return new OpResult.FormatCorrupt("pack", "缺少 materials 或 spikes 字段");
        }
        String opId = Store.newId("op");
        db.beginOp(opId, "import pack");
        try {
            int mi = 0;
            for (JsonNode m : pack.get("materials")) {
                if (!m.has("kind") || !m.has("payload")) {
                    return new OpResult.FormatCorrupt("materials[" + mi + "]", "缺少 kind/payload");
                }
                String id = m.has("id") ? m.get("id").asText() : Store.newId("mat");
                var payload = m.get("payload");
                store.insertMaterial(new Material(id, m.get("kind").asText(),
                        m.has("version") ? m.get("version").asInt() : 1, payload,
                        Store.digest(payload.toString()), db.nextSeq()), opId);
                mi++;
            }
            int si = 0;
            for (JsonNode s : pack.get("spikes")) {
                if (!s.has("unit_id")) {
                    return new OpResult.FormatCorrupt("spikes[" + si + "]", "缺少 unit_id");
                }
                insertSpike(s, s.get("unit_id").asText(), "spikes[" + si + "]",
                        s.has("waveform_ref_id") ? s.get("waveform_ref_id").asText() : null, opId);
                si++;
            }
            db.commitOp(opId);
            return new OpResult.Ok("imported materials=" + mi + " spikes=" + si, java.util.List.of(opId));
        } catch (RuntimeException e) {
            db.recover();
            return new OpResult.FormatCorrupt("pack", e.getMessage());
        }
    }

    /** 插入单条棘波：原始表示入库，规范化失败时留诊断、不丢值。 */
    public void insertSpike(JsonNode s, String unitId, String position, String waveformRefId, String opId) {
        String tRaw = s.has("t") ? s.get("t").asText() : null;
        String ampRaw = s.has("amplitude") ? s.get("amplitude").asText() : null;
        var t = Quantity.parse(tRaw, position + ".t",
                (pos, msg) -> diag("warn", pos, msg, tRaw, null));
        var amp = Quantity.parse(ampRaw, position + ".amplitude",
                (pos, msg) -> diag("warn", pos, msg, ampRaw, null));
        double f1 = amp.normalized() != null ? amp.normalized() : 0;
        double f2 = t.normalized() != null ? (t.normalized() % 97.0) : 0; // 派生特征占位
        Integer channel = s.has("channel") ? s.get("channel").asInt() : null;
        store.insertSpike(new Spike(Store.newId("sp"), unitId, tRaw, t.normalized(), channel,
                ampRaw, amp.normalized(), waveformRefId, f1, f2), opId);
    }

    private void diag(String severity, String position, String message, String raw, String materialId) {
        store.insertDiagnostic(new Diagnostic(Store.newId("diag"), severity, position, message, raw, materialId));
    }
}
