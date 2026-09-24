package neurospike;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads and validates an offline domain case pack. Import keeps original
 * representations; only the derived layer normalizes. Incomplete input yields
 * located diagnostics - never nulls/defaults pretending to be success.
 */
public final class CaseLoader {

    private CaseLoader() {
    }

    public static ImportOutcome load(byte[] bytes, ResourceBudget budget) {
        ResourceBudget counted = budget.withBytes(bytes.length);
        if (counted.bytes() > counted.maxBytes()) {
            return new ImportOutcome.BudgetExhausted(new Diagnostic(
                    Diagnostic.BUDGET_EXHAUSTED, "",
                    "input exceeds byte budget " + counted.maxBytes(),
                    Integer.toString(bytes.length)));
        }
        JsonFactory factory = new JsonFactory();
        JsonNode root;
        try (JsonParser p = factory.createParser(bytes)) {
            // streaming node-count gate before materializing the tree
            ResourceBudget b = counted;
            JsonToken token;
            while ((token = p.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY
                        || token == JsonToken.VALUE_STRING
                        || token == JsonToken.VALUE_NUMBER_INT
                        || token == JsonToken.VALUE_NUMBER_FLOAT
                        || token == JsonToken.VALUE_BOOLEAN) {
                    b = b.tickNode();
                }
            }
            root = Json.MAPPER.readTree(bytes);
        } catch (ResourceBudget.BudgetExceededException e) {
            return new ImportOutcome.BudgetExhausted(e.diagnostic);
        } catch (IOException e) {
            return new ImportOutcome.FormatCorrupt(List.of(new Diagnostic(
                    Diagnostic.FORMAT_CORRUPT, "",
                    "JSON not parseable: " + e.getMessage(), null)));
        }
        if (root == null || !root.isObject()) {
            return new ImportOutcome.FormatCorrupt(List.of(new Diagnostic(
                    Diagnostic.FORMAT_CORRUPT, "",
                    "case pack root must be a JSON object", null)));
        }

        List<Diagnostic> semantic = new ArrayList<>();
        List<Diagnostic> warnings = new ArrayList<>();
        require(root, "/caseId", semantic);
        require(root, "/timeBasis", semantic);
        require(root, "/probe", semantic);
        require(root, "/thresholds", semantic);
        require(root, "/waveforms", semantic);
        require(root, "/detections", semantic);
        require(root, "/featureSpec", semantic);
        require(root, "/labelSets", semantic);
        if (!semantic.isEmpty()) {
            return new ImportOutcome.SemanticUnresolvable(semantic);
        }
        if (root.path("detections").size() > counted.maxDetections()) {
            return new ImportOutcome.BudgetExhausted(new Diagnostic(
                    Diagnostic.BUDGET_EXHAUSTED, "/detections",
                    "detection count exceeds budget", null));
        }

        validateReferential(root, semantic, warnings, counted);
        validateQuantities(root, semantic, warnings);
        if (!semantic.isEmpty()) {
            return new ImportOutcome.SemanticUnresolvable(semantic);
        }

        try {
            CasePack pack = Json.MAPPER.treeToValue(root, CasePack.class);
            if (!CasePack.DOMAIN.equals(pack.domain())) {
                warnings.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                        "/domain", "domain marker is not the domain pack; refusing "
                                + "to treat generic table as domain material",
                        pack.domain()));
            }
            return new ImportOutcome.Loaded(pack, warnings);
        } catch (Exception e) {
            return new ImportOutcome.FormatCorrupt(List.of(new Diagnostic(
                    Diagnostic.FORMAT_CORRUPT, "",
                    "structure parse failed: " + e.getMessage(), null)));
        }
    }

    private static void require(JsonNode root, String field, List<Diagnostic> out) {
        JsonNode node = root.get(field.substring(1));
        if (node == null || node.isNull()) {
            out.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE, field,
                    "required layer missing", null));
        }
    }

    private static void validateReferential(JsonNode root, List<Diagnostic> semantic,
                                            List<Diagnostic> warnings,
                                            ResourceBudget budget) {
        Set<String> waveformIds = new HashSet<>();
        Set<String> channelIds = new HashSet<>();
        for (JsonNode site : root.path("probe").path("sites")) {
            if (site.has("channelId")) {
                channelIds.add(site.path("channelId").asText());
            }
        }
        long samplesTotal = 0;
        JsonNode wfs = root.path("waveforms");
        for (int i = 0; i < wfs.size(); i++) {
            JsonNode w = wfs.get(i);
            String id = w.path("waveformId").asText(null);
            if (id == null || !waveformIds.add(id)) {
                semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                        "/waveforms/" + i + "/waveformId",
                        "missing or duplicate waveformId", id));
            }
            if (!channelIds.contains(w.path("channelId").asText())) {
                semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                        "/waveforms/" + i + "/channelId",
                        "waveform channel not present in probe geometry",
                        w.path("channelId").asText()));
            }
            if (w.has("samples")) {
                samplesTotal += w.path("samples").size();
            }
        }
        if (samplesTotal > budget.maxWaveformSamples()) {
            semantic.add(new Diagnostic(Diagnostic.BUDGET_EXHAUSTED,
                    "/waveforms", "waveform sample budget exceeded",
                    Long.toString(samplesTotal)));
        }

        Set<String> detectionIds = new HashSet<>();
        JsonNode dets = root.path("detections");
        for (int i = 0; i < dets.size(); i++) {
            JsonNode d = dets.get(i);
            String id = d.path("detectionId").asText(null);
            if (id == null || !detectionIds.add(id)) {
                semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                        "/detections/" + i + "/detectionId",
                        "missing or duplicate detectionId", id));
            }
            String wf = d.path("waveformId").asText(null);
            if (wf != null && !waveformIds.contains(wf)) {
                semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                        "/detections/" + i + "/waveformId",
                        "detection references unknown waveform", wf));
            }
            if (!channelIds.contains(d.path("channelId").asText())) {
                semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                        "/detections/" + i + "/channelId",
                        "detection channel not present in probe geometry",
                        d.path("channelId").asText()));
            }
        }
        for (int i = 0; i < dets.size(); i++) {
            JsonNode d = dets.get(i);
            for (JsonNode other : d.path("concurrentWith")) {
                if (!detectionIds.contains(other.asText())) {
                    semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                            "/detections/" + i + "/concurrentWith",
                            "concurrence reference unknown", other.asText()));
                }
            }
            for (JsonNode other : d.path("propagatedTo")) {
                if (!detectionIds.contains(other.asText())) {
                    semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                            "/detections/" + i + "/propagatedTo",
                            "propagation reference unknown", other.asText()));
                }
            }
        }

        JsonNode labels = root.path("labelSets");
        for (int i = 0; i < labels.size(); i++) {
            JsonNode l = labels.get(i);
            JsonNode map = l.path("detectionCluster");
            if (!map.isObject()) {
                semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                        "/labelSets/" + i + "/detectionCluster",
                        "label mapping must be an object detectionId->cluster number",
                        null));
                continue;
            }
            map.fields().forEachRemaining(e -> {
                if (!detectionIds.contains(e.getKey())) {
                    semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                            "/labelSets/" + i + "/detectionCluster/" + e.getKey(),
                            "label for unknown detection", e.getKey()));
                }
                if (!e.getValue().canConvertToInt()) {
                    semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                            "/labelSets/" + i + "/detectionCluster/" + e.getKey(),
                            "cluster label must be an integer", e.getValue().asText()));
                }
            });
        }
    }

    private static void validateQuantities(JsonNode root, List<Diagnostic> semantic,
                                           List<Diagnostic> warnings) {
        JsonNode tb = root.path("timeBasis");
        if (!tb.path("samplesPerSecond").isNumber()) {
            semantic.add(new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE,
                    "/timeBasis/samplesPerSecond",
                    "sample clock numeric value required",
                    tb.path("samplesPerSecond").toString()));
        }
        for (int i = 0; i < root.path("detections").size(); i++) {
            JsonNode amp = root.path("detections").get(i).path("peakAmplitude");
            if (amp.isObject() && "UNCONVERTIBLE".equals(amp.path("status").asText())) {
                warnings.add(new Diagnostic(Diagnostic.LOCAL_VALUE_INVALID,
                        "/detections/" + i + "/peakAmplitude",
                        "amplitude kept in place, unconvertible; drift becomes local failure",
                        amp.toString()));
            }
        }
    }
}
