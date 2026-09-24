package org.neurospike.engine;

import org.neurospike.model.Derived;
import org.neurospike.model.Ids;
import org.neurospike.store.ProjectStore;
import org.neurospike.store.StoreLabels;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 谱系引擎：跨标签版本比较簇成员集合，产出 MERGE/SPLIT/RENUMBER/SUPERSEDE 边。
 * 簇身份是成员集合（clusterKey），显示编号变化只产生 RENUMBER，不产生新身份。
 */
public final class LineageEngine {
    private final ProjectStore store;

    public LineageEngine(ProjectStore store) { this.store = store; }

    public void buildEdges(String parentLabelSetId, String childLabelSetId,
                           int parentVersion, int childVersion, String evidenceNodeId) {
        try {
            Map<String, StoreLabels.Cluster> parent = store.labels().clusters(parentLabelSetId);
            Map<String, StoreLabels.Cluster> child = store.labels().clusters(childLabelSetId);

            for (Map.Entry<String, StoreLabels.Cluster> pe : parent.entrySet()) {
                Set<String> pMembers = new LinkedHashSet<>(pe.getValue().eventIds());
                for (Map.Entry<String, StoreLabels.Cluster> ce : child.entrySet()) {
                    Set<String> cMembers = new LinkedHashSet<>(ce.getValue().eventIds());
                    Set<String> shared = new LinkedHashSet<>(pMembers);
                    shared.retainAll(cMembers);
                    if (shared.isEmpty()) continue;

                    String relation;
                    String sharedEvent = null;
                    if (pe.getKey().equals(ce.getKey())
                            && pMembers.equals(cMembers)
                            && pe.getValue().displayNumber() != ce.getValue().displayNumber()) {
                        relation = "RENUMBER";
                    } else if (pe.getKey().equals(ce.getKey()) && pMembers.equals(cMembers)) {
                        relation = "IDENTITY";
                    } else if (pMembers.size() > cMembers.size()) {
                        relation = "SPLIT";
                        sharedEvent = shared.iterator().next();
                    } else if (pMembers.size() < cMembers.size()) {
                        relation = "MERGE";
                        sharedEvent = shared.iterator().next();
                    } else {
                        relation = "OVERLAP";
                        sharedEvent = shared.iterator().next();
                    }
                    if (relation.equals("IDENTITY")) continue;
                    String edgeId = Ids.contentId("edge", String.join("|",
                            relation, pe.getKey(), ce.getKey(),
                            String.valueOf(parentVersion), String.valueOf(childVersion),
                            sharedEvent == null ? "" : sharedEvent));
                    store.derived().putLineageEdge(new Derived.LineageEdge(edgeId, relation,
                            pe.getKey(), ce.getKey(), parentVersion, childVersion,
                            sharedEvent, evidenceNodeId));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("lineage build failed", e);
        }
    }

    /** 仅返回与某个事件相关的下游簇键（用于“裁定后显示受影响下游记录”）。 */
    public Set<String> downstreamClusters(String eventId, String labelSetId) {
        try {
            Set<String> out = new LinkedHashSet<>();
            store.labels().clusters(labelSetId).values().stream()
                    .filter(c -> c.eventIds().contains(eventId))
                    .forEach(c -> out.add(c.clusterKey()));
            // 经由谱系边扩展：曾与该簇发生过合并/拆分/重编号的所有簇
            Set<String> frontier = new HashSet<>(out);
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Derived.LineageEdge e : store.derived().lineageEdges()) {
                    String other = null;
                    if (frontier.contains(e.fromClusterKey())) other = e.toClusterKey();
                    else if (frontier.contains(e.toClusterKey())) other = e.fromClusterKey();
                    if (other != null && out.add(other)) { frontier.add(other); grew = true; }
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
