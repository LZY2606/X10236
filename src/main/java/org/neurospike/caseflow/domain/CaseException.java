package org.neurospike.caseflow.domain;

public class CaseException extends RuntimeException {
    public enum Kind {
        SEMANTIC_UNSOLVABLE,
        FORMAT_CORRUPT,
        BUDGET_EXHAUSTED
    }

    private final Kind kind;
    private final transient Diagnostic diagnostic;

    public CaseException(Kind kind, Diagnostic diagnostic) {
        super(diagnostic.message());
        this.kind = kind;
        this.diagnostic = diagnostic;
    }

    public Kind kind() {
        return kind;
    }

    public Diagnostic diagnostic() {
        return diagnostic;
    }
}
