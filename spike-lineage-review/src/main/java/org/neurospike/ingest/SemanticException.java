package org.neurospike.ingest;

import org.neurospike.domain.Diagnostic;

import java.util.List;

/** Parsed fine but the meaning cannot be resolved (e.g. dangling reference, unknown frame). */
public class SemanticException extends RuntimeException {
    private final List<Diagnostic> diagnostics;

    public SemanticException(String message, List<Diagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
