package neurospike.model;

import java.util.List;

/**
 * Result of ingesting one case package. A locally-invalid import may still be
 * {@code accepted} (bad rows retained with diagnostics); structural/semantic
 * failures reject the whole batch and insert nothing.
 */
public record ImportResult(Outcome outcome, boolean committed, String sessionId,
                           List<Diagnostic> diagnostics, int insertedMaterials) {

    public boolean ok() {
        return outcome == Outcome.OK;
    }
}
