package neuro.spike.provenance;

import java.util.List;

/** Resource budget (event cap / step cap) exhausted - distinct from corruption. */
public class BudgetExhaustedException extends RuntimeException {
    private final List<Diagnostic> diagnostics;

    public BudgetExhaustedException(String path, String message) {
        super(message);
        this.diagnostics = List.of(Diagnostic.budget(path, message));
    }

    public List<Diagnostic> diagnostics() { return diagnostics; }
}
