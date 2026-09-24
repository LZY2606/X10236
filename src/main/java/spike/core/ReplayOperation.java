package spike.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One replay step. Import steps are immutable-material writes; decision steps
 * are curator actions. A replay is the deterministic spine of the case pack.
 */
public record ReplayOperation(
        long seq,
        String type,
        String label,
        JsonNode payload,
        String expectsOutcome,
        String expectsDiagnosticCode
) {
    public static final String IMPORT_BASELINE = "import_baseline";
    public static String IMPORT_MATERIAL = "import_material";
    public static final String PARAM_REVISION = "param_revision";
    public static final String SOURCE_PATCH = "source_patch";
    public static final String CONFLICTING_DECISION = "conflicting_decision";
    public static final String WINDOW_CHANGE = "window_change";
    public static final String ADJUDICATE = "adjudicate";
}
