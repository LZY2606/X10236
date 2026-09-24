package org.neurospike.store;

import org.neurospike.model.Derived;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 派生 DAG 与谱系边的读写。节点内容寻址，天然幂等。 */
public final class StoreDerived {
    private final Connection c;

    public StoreDerived(Connection c) { this.c = c; }

    public boolean nodeExists(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM derived_node WHERE node_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public Derived.Node node(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM derived_node WHERE node_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Derived.Node(rs.getString("node_id"),
                        Derived.NodeKind.valueOf(rs.getString("kind")),
                        rs.getString("status"), rs.getString("input_digest"),
                        rs.getString("param_version"), rs.getString("payload_json"),
                        parents(id), rs.getLong("birth_decision_seq"),
                        rs.getInt("retired") == 1);
            }
        }
    }

    public List<String> parents(String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT parent_id FROM derived_parent WHERE node_id=? ORDER BY pos")) {
            ps.setString(1, id);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString("parent_id"));
            }
            return out;
        }
    }

    public void putNode(Derived.Node n) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO derived_node(node_id,kind,status,input_digest,param_version,
                  payload_json,birth_decision_seq,retired)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(node_id) DO NOTHING
                """)) {
            ps.setString(1, n.nodeId()); ps.setString(2, n.kind().name());
            ps.setString(3, n.status()); ps.setString(4, n.inputDigest());
            ps.setString(5, n.paramVersion()); ps.setString(6, n.payloadJson());
            ps.setLong(7, n.birthDecisionSeq()); ps.setInt(8, n.retired() ? 1 : 0);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO derived_parent(node_id,parent_id,pos) VALUES(?,?,?)
                ON CONFLICT(node_id,parent_id) DO NOTHING
                """)) {
            List<String> p = n.parentIds();
            for (int i = 0; i < p.size(); i++) {
                ps.setString(1, n.nodeId()); ps.setString(2, p.get(i)); ps.setInt(3, i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public List<Derived.Node> nodesByKind(Derived.NodeKind kind) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM derived_node WHERE kind=? ORDER BY node_id")) {
            ps.setString(1, kind.name());
            List<Derived.Node> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Derived.Node(rs.getString("node_id"),
                            Derived.NodeKind.valueOf(rs.getString("kind")),
                            rs.getString("status"), rs.getString("input_digest"),
                            rs.getString("param_version"), rs.getString("payload_json"),
                            parents(rs.getString("node_id")),
                            rs.getLong("birth_decision_seq"), rs.getInt("retired") == 1));
                }
            }
            return out;
        }
    }

    public List<Derived.Node> allNodes() throws SQLException {
        List<Derived.Node> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM derived_node ORDER BY birth_decision_seq,kind,node_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("node_id");
                out.add(new Derived.Node(id, Derived.NodeKind.valueOf(rs.getString("kind")),
                        rs.getString("status"), rs.getString("input_digest"),
                        rs.getString("param_version"), rs.getString("payload_json"),
                        parents(id), rs.getLong("birth_decision_seq"),
                        rs.getInt("retired") == 1));
            }
        }
        return out;
    }

    public void putLineageEdge(Derived.LineageEdge edge) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO lineage_edge(edge_id,relation,from_cluster_key,to_cluster_key,
                  from_label_version,to_label_version,shared_event_id,evidence_node_id)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(edge_id) DO NOTHING
                """)) {
            ps.setString(1, edge.edgeId()); ps.setString(2, edge.relation());
            ps.setString(3, edge.fromClusterKey()); ps.setString(4, edge.toClusterKey());
            ps.setInt(5, edge.fromLabelVersion()); ps.setInt(6, edge.toLabelVersion());
            ps.setString(7, edge.sharedEventId()); ps.setString(8, edge.evidenceNodeId());
            ps.executeUpdate();
        }
    }

    public List<Derived.LineageEdge> lineageEdges() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM lineage_edge ORDER BY from_label_version,relation,edge_id");
             ResultSet rs = ps.executeQuery()) {
            List<Derived.LineageEdge> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Derived.LineageEdge(rs.getString("edge_id"),
                        rs.getString("relation"), rs.getString("from_cluster_key"),
                        rs.getString("to_cluster_key"), rs.getInt("from_label_version"),
                        rs.getInt("to_label_version"), rs.getString("shared_event_id"),
                        rs.getString("evidence_node_id")));
            }
            return out;
        }
    }
}
