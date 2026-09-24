package neurospike.model;

import java.util.List;

/**
 * Outcome of a computation request. {@code BUDGET_EXHAUSTED} is distinct from
 * corrupt or unresolvable inputs. Diagnostics may carry result-local positions.
 */
public record EngineResult(Outcome outcome, String resultId, List<Diagnostic> diagnostics) {
    public boolean ok() {
        return outcome == Outcome.OK;
    }
}
