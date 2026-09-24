package neurospike;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Analysis window. Always half-open [start,end) in seconds on the recording
 * time basis. Changing the window recomputes only clusters whose detections
 * intersect it.
 */
public record WindowSpec(double startSeconds, double endSeconds, String basis,
                         String unit) {

    public static WindowSpec record(State state) {
        double maxT = 0;
        double hz = state.timeBasis().samplesPerSecond().value();
        for (MaterialRev<Detection> d : state.detectionVersions()) {
            if (d.material().active() && d.material().sample() != null
                    && d.material().sample().ok()) {
                maxT = Math.max(maxT, d.material().sample().value() / hz);
            }
        }
        return new WindowSpec(0.0, Math.ceil(maxT * 1000) / 1000.0,
                state.timeBasis().basisId(), "s");
    }

    public static WindowSpec fromNode(JsonNode node, WindowSpec fallback) {
        if (node == null || !node.has("startSeconds")) {
            return fallback;
        }
        return new WindowSpec(node.path("startSeconds").asDouble(
                        fallback.startSeconds()),
                node.path("endSeconds").asDouble(fallback.endSeconds()),
                node.path("basis").asText(fallback.basis()),
                node.path("unit").asText(fallback.unit()));
    }

    public boolean contains(double tSeconds) {
        return tSeconds >= startSeconds && tSeconds < endSeconds;
    }

    public boolean intersects(List<Detection> detections, double hz) {
        for (Detection d : detections) {
            if (d.sample() == null || !d.sample().ok()) {
                continue;
            }
            double t = d.sample().value() / hz;
            if (contains(t)) {
                return true;
            }
        }
        return false;
    }
}
