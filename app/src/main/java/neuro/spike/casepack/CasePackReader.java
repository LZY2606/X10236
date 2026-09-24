package neuro.spike.casepack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import neuro.spike.util.Json;
import neuro.spike.util.ImportFailure.FormatCorruptException;
import neuro.spike.util.Diagnostic;

/**
 * Reads the bundled offline case pack (or an external directory). Every parse problem
 * becomes FORMAT_CORRUPT with a JSON Pointer position — never a silent default.
 */
public final class CasePackReader {
    private final ObjectMapper mapper = Json.MAPPER;

    public CasePack readDirectory(Path dir) {
        Path manifestPath = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw corrupt("manifest.json", "manifest.json missing", "");
        }
        JsonNode manifestNode;
        try {
            manifestNode = mapper.readTree(Files.readAllBytes(manifestPath));
        } catch (IOException e) {
            throw corrupt("/manifest.json", e.getMessage(), "");
        }
        CasePack.Manifest manifest = parseManifest(manifestNode);

        List<JsonNode> files = new ArrayList<>();
        for (String fileName : manifest.files()) {
            Path p = dir.resolve(fileName);
            if (!Files.isRegularFile(p)) {
                throw corrupt("/files", "declared file missing: " + fileName, fileName);
            }
            try {
                files.add(mapper.readTree(Files.readAllBytes(p)));
            } catch (IOException e) {
                throw corrupt("/" + fileName, e.getMessage(), fileName);
            }
        }
        return new CasePack(manifest, files);
    }

    public CasePack readClasspath(String resourceDir) {
        String manifestResource = resourceDir + "/manifest.json";
        try (InputStream in = getClass().getResourceAsStream(manifestResource)) {
            if (in == null) {
                throw corrupt(manifestResource, "bundled manifest missing", "");
            }
            JsonNode manifestNode = mapper.readTree(in);
            CasePack.Manifest manifest = parseManifest(manifestNode);
            List<JsonNode> files = new ArrayList<>();
            for (String fileName : manifest.files()) {
                try (InputStream fin = getClass().getResourceAsStream(resourceDir + "/" + fileName)) {
                    if (fin == null) {
                        throw corrupt(fileName, "bundled file missing: " + fileName, fileName);
                    }
                    files.add(mapper.readTree(fin));
                }
            }
            return new CasePack(manifest, files);
        } catch (IOException e) {
            throw new IllegalStateException("bundled case pack unreadable", e);
        }
    }

    private CasePack.Manifest parseManifest(JsonNode node) {
        require(node, "id", "/id");
        require(node, "title", "/title");
        require(node, "version", "/version");
        JsonNode budgetNode = node.path("budget");
        if (budgetNode.isMissingNode()) {
            throw corrupt("/budget", "budget missing", "");
        }
        CasePack.Budget budget = new CasePack.Budget(
                intField(budgetNode, "maxDetections", "/budget/maxDetections"),
                intField(budgetNode, "maxSamples", "/budget/maxSamples"),
                intField(budgetNode, "maxDerivedRows", "/budget/maxDerivedRows"));
        JsonNode stepsNode = node.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            throw corrupt("/steps", "steps must be a non-empty array", "");
        }
        List<CasePack.Step> steps = new ArrayList<>();
        for (int i = 0; i < stepsNode.size(); i++) {
            JsonNode s = stepsNode.get(i);
            require(s, "kind", "/steps/" + i + "/kind");
            require(s, "file", "/steps/" + i + "/file");
            steps.add(new CasePack.Step(s.get("kind").asText(),
                    s.get("file").asText(),
                    s.path("note").asText("")));
        }
        JsonNode filesNode = node.path("files");
        if (!filesNode.isArray() || filesNode.isEmpty()) {
            throw corrupt("/files", "files must be a non-empty array", "");
        }
        List<String> files = new ArrayList<>();
        filesNode.forEach(f -> files.add(f.asText()));
        return new CasePack.Manifest(node.get("id").asText(), node.get("title").asText(),
                node.get("version").asText(), budget, steps, files);
    }

    private void require(JsonNode node, String field, String pointer) {
        if (!node.hasNonNull(field)) {
            throw corrupt(pointer, "required field missing: " + field, "");
        }
    }

    private int intField(JsonNode node, String field, String pointer) {
        require(node, field, pointer);
        JsonNode v = node.get(field);
        if (!v.canConvertToInt()) {
            throw corrupt(pointer, "integer required: " + field, v.asText());
        }
        return v.intValue();
    }

    private FormatCorruptException corrupt(String position, String message, String raw) {
        return new FormatCorruptException(message,
                List.of(new Diagnostic("F-PARSE", position, message, raw)));
    }
}
