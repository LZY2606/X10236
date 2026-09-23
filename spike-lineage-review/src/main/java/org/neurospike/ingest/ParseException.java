package org.neurospike.ingest;

import org.neurospike.domain.Diagnostic;

import java.util.List;

/** Format-level corruption: distinct from semantic failure and budget exhaustion. */
public class ParseException extends RuntimeException {
    private final List<Diagnostic> diagnostics;

    public ParseException(String message, List<Diagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
