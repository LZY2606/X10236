package com.neuro.gsb.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only adjudication / revision event log. Events are the only mutable
 * view of the world: conclusions are derived by folding events, and replaying
 * "as of seq N" restores the dependency versions and visible conclusions of
 * that moment instead of overwriting history with fresh computation.
 */
public final class EventStore {
    public record Event(long seq, String id, String kind, String payload,
                        List<String> parents, String createdAt) {}

    private final Database db;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public EventStore(Database db) { this.db = db; }

    public Event append(Connection c, String kind, String payload, List<String> parents) {
        String id = "evt-" + UUID.randomUUID();
        try (var ps = c.prepareStatement(
                "INSERT INTO event(id,kind,payload,parents,created_at) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, id);
            ps.setString(2, kind);
            ps.setString(3, payload);
            ps.setString(4, json.writeValueAsString(parents));
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Event(keys.getLong(1), id, kind, payload, parents, "");
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Event> upTo(long asOfSeq) {
        List<Event> out = new ArrayList<>();
        try (var ps = db.conn().prepareStatement(
                "SELECT seq,id,kind,payload,parents,created_at FROM event WHERE seq<=? ORDER BY seq")) {
            ps.setLong(1, asOfSeq);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Event(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), parseParents(rs.getString(5)), rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Optional<Event> bySeq(long seq) {
        return upTo(seq).stream().filter(e -> e.seq == seq).findFirst();
    }

    public long latestSeq() {
        try (var st = db.conn().createStatement();
             var rs = st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM event")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseParents(String raw) {
        try {
            return json.readValue(raw, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
