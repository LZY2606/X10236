package neuro.spike.casepack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Offline domain case pack: "Pulse-cluster lineage: neural spike candidates, drift
 * clusters and human adjudication genealogy". A pack is an ordered directory of JSON
 * materials plus a manifest declaring replay steps and the resource budget.
 */
public record CasePack(Manifest manifest, List<JsonNode> files) {

    public record Manifest(String id, String title, String version,
                           Budget budget, List<Step> steps, List<String> files) {
    }

    public record Budget(int maxDetections, int maxSamples, int maxDerivedRows) {
    }

    public record Step(String kind, String file, String note) {
    }
}
