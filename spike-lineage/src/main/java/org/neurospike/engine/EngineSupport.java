package org.neurospike.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.neurospike.model.Derived;
import org.neurospike.model.Ids;
import org.neurospike.store.ProjectStore;

import java.util.List;
import java.util.stream.Collectors;

/** 派生节点构造助手：nodeId 内容寻址（kind + 参数版本 + 输入摘要 + 父节点）。 */
final class EngineSupport {
    private EngineSupport() {}

    static Derived.Node node(ProjectStore store, Derived.NodeKind kind, String status,
                             String inputDigest, EngineContext ctx, Object payload,
                             List<Derived.Node> parents, long birthSeq) {
        ObjectMapper json = store.json();
        String payloadJson;
        try { payloadJson = json.writeValueAsString(payload); }
        catch (Exception e) { throw new RuntimeException(e); }
        String parentKey = parents.stream().map(Derived.Node::nodeId)
                .collect(Collectors.joining(","));
        String id = Ids.contentId("node",
                kind + "|" + ctx.canonical() + "|" + inputDigest + "|" + parentKey);
        List<String> parentIds = parents.stream().map(Derived.Node::nodeId).toList();
        return new Derived.Node(id, kind, status, inputDigest, ctx.canonical(),
                payloadJson, parentIds, birthSeq, false);
    }
}
