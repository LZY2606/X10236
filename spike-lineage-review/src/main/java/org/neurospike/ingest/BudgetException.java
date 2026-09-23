package org.neurospike.ingest;

import org.neurospike.domain.Diagnostic;

/** Resource budget exhausted (rows / bytes). Never reported as corruption or semantics. */
public class BudgetException extends RuntimeException {
    private final transient Diagnostic diagnostic;

    public BudgetException(Diagnostic diagnostic) {
        super(diagnostic.message());
        this.diagnostic = diagnostic;
    }

    public Diagnostic diagnostic() {
        return diagnostic;
    }
}
