package spike.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import spike.model.Adjudication;
import spike.model.ClusteringRun;
import spike.model.ClusterMembershipRow;
import spike.model.Diagnostic;
import spike.model.Enums.DecisionType;
import spike.model.Enums.EvidenceType;
import spike.model.Enums.LineageType;
import spike.model.Enums.Membership;
import spike.model.Enums.EventStatus;
import spike.model.Evidence;
import spike.model.FeatureVersion;
import spike.model.LedgerEvent;
import spike.model.LineageLink;
import spike.model.ProbeChannel;
import spike.model.ProbeGeometry;
import spike.model.Quantity;
import spike.model.SpikeEvent;
import spike.model.Threshold;
import spike.model.WaveformRef;

/** Directly testable storage layer (no HTTP involved). */
public class Repository {
    private static final ObjectMapper M = new ObjectMapper();

    private final Db db;

    public Repository(Db db) {
        this.db = db;
    }

    public Db db() {
        return db;
    }

    private static String join(List<String> xs) {
        return String.join(",", xs == null ? List.of() : xs);
    }

    private static List<String> split(String s) {
        if (s == null || s.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(s.split(",")));
    }

    private static String toJson(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("json encode", e);
        }
    }

    private static List<Double> fromJson(String s) {
        try {
            List<Double> out = new ArrayList<>();
            M.readTree(s).forEach(n -> out.add(n.asDouble()));
            return out;
        } catch (Exception e) {
            throw new RuntimeException("json decode", e);
        }
    }

    // ---- meta -----------------------------------------------------------

    public void setMeta(String key, String value) {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) "
                        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public String getMeta(String key) {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
