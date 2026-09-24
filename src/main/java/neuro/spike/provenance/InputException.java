package neuro.spike.provenance;

import java.util.List;

/** Raised for malformed input. Carries one or more located diagnostics. */
public class InputException extends RuntimeException {
    private final List<Diagnostic> diagnostics;

    public InputException(List<Diagnostic> diagnostics) {
        super(diagnostics.isEmpty() ? "input error" : diagnostics.get(0).toLine());
        this.diagnostics = List.copyOf(diagnostics);
    }

    public InputException(Diagnostic d) {
        this(List.of(d));
    }

    public List<Diagnostic> diagnostics() { return diagnostics; }
}
