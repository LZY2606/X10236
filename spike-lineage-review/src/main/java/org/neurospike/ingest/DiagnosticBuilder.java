package org.neurospike.ingest;

import org.neurospike.domain.Diagnostic;

import java.util.Map;

final class DiagnosticBuilder {
    private DiagnosticBuilder() {
    }

    static String unconvertible(String raw, String message) {
        return message + " [raw=" + raw + "]";
    }

    static Diagnostic corrupt(String file, int line, int column, String message, String raw) {
        return new Diagnostic(Diagnostic.CORRUPT_FORMAT, file, line, column, message, raw, Map.of());
    }

    static Diagnostic semantic(String file, int line, int column, String message, String raw) {
        return new Diagnostic(Diagnostic.SEMANTIC_UNRESOLVABLE, file, line, column, message, raw,
                Map.of());
    }
}
