package neurogsb.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public final class Records {
    public record Material(String id, String kind, int version, JsonNode payload, String digest, long seq) {}
    public record Spike(String id, String unitId, String tRaw, Double tMs, Integer channel,
                        String ampRaw, Double ampUv, String waveformRefId, double f1, double f2) {}
    public record Event(String id, long seq, String kind, String actor, String targetUnitId,
                        JsonNode payload, List<String> parents, String branch) {}
    public record Derived(String id, String kind, String unitId, String inputDigest, String paramVersion,
                          List<String> parents, String status, JsonNode payload, long eventSeq) {}
    public record Invalidation(String id, String derivedId, String reason, String scope, long eventSeq) {}
    public record Candidate(String id, String subjectId, String kind, JsonNode payload, String status) {}
    public record Diagnostic(String id, String severity, String position, String message, String rawValue, String materialId) {}
    private Records() {}
}
