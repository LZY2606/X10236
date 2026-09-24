package org.neurospike.adjudicate;

import com.fasterxml.jackson.databind.JsonNode;
import org.neurospike.model.Material;
import org.neurospike.store.ProjectStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史回放：不修改当前状态，而是重放到指定 seq 时的参数版本，
 * 在临时上下文中重建派生节点 id 集合（不写库），返回“当时可见结论”。
 * 最新计算结果永远不会覆盖历史。
 */
public final class HistoryService {
    private final ProjectStore store;

    public HistoryService(ProjectStore store) { this.store = store; }

    public Map<String, Object> snapshotAt(long seq) {
        try {
            List<Material.Adjudication> all = store.decision().history("main");
            List<Material.Adjudication> upto = all.stream().filter(a -> a.seq() <= seq).toList();
            // 从基线参数出发，顺序重放参数类事件
            String labelSet = "ls-v1";
            int thresholdVersion = 1;
            double winStart = 0.0, winEnd = 1e12, tol = 0.001, ref = 0.001;
            String patch = null;
            for (Material.Adjudication a : upto) {
                JsonNode p = store.json().readTree(a.payload());
                switch (a.kind()) {
                    case "param-threshold" -> thresholdVersion = p.path("to").asInt();
                    case "param-window" -> {
                        winStart = p.path("to").get(0).asDouble();
                        winEnd = p.path("to").get(1).asDouble();
                    }
                    case "param-labels" -> labelSet = p.path("to").asText();
                    case "source-patch" -> patch = p.path("source-patch").asText()
                            + "#" + p.path("supersedesOrder").asInt()
                            + "=" + p.path("newAmplitude").asText();
                    default -> { }
                }
            }
            var ctxCanonical = String.join("|", labelSet, String.valueOf(thresholdVersion),
                    fmt(winStart), fmt(winEnd), fmt(tol), fmt(ref), patch == null ? "" : patch);

            // 当时存在的节点 = birth_decision_seq <= seq（以入库时记录的派生生成数近似）
            List<Map<String, Object>> nodesThen = new ArrayList<>();
            List<String> decisions = new ArrayList<>();
            for (Material.Adjudication a : upto) decisions.add(a.kind() + "#" + a.seq());
            for (var n : store.derived().allNodes()) {
                if (n.paramVersion().equals(ctxCanonical) || isParamCompatible(n, ctxCanonical)) {
                    Map<String, Object> nm = new LinkedHashMap<>();
                    nm.put("nodeId", n.nodeId()); nm.put("kind", n.kind().name());
                    nm.put("status", n.status());
                    nodesThen.add(nm);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("seq", seq);
            out.put("params", Map.of("labelSet", labelSet, "thresholdVersion", thresholdVersion,
                    "window", List.of(winStart, winEnd >= 1e11 ? "inf" : winEnd),
                    "sourcePatch", patch == null ? "" : patch));
            out.put("decisions", decisions);
            out.put("visibleNodes", nodesThen);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("snapshot failed", e);
        }
    }

    private boolean isParamCompatible(org.neurospike.model.Derived.Node n, String canonical) {
        // 仅历史浏览用的宽松匹配：同标签集+阈值版本即归入该时刻视图（窗口视图另有 paramVersion 精确值）
        String[] a = n.paramVersion().split("\\|");
        String[] b = canonical.split("\\|");
        return a.length >= 2 && b.length >= 2 && a[0].equals(b[0]) && a[1].equals(b[1]);
    }

    private static String fmt(double v) {
        if (v >= 1e11) return "inf";
        return String.format(java.util.Locale.ROOT, "%.6f", v);
    }
}
