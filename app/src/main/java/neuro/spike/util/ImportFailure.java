package neuro.spike.util;

import java.util.List;

/**
 * The three mandated, mutually distinct failure outcomes.
 * FORMAT_CORRUPT: the representation cannot be parsed / schema violated.
 * SEMANTIC_UNRESOLVED: parsed, but no unique legal meaning (all legal candidates retained elsewhere).
 * BUDGET_EXHAUSTED: well-formed and meaningful, but the declared resource budget was exceeded.
 */
public enum ImportFailure {
    FORMAT_CORRUPT,
    SEMANTIC_UNRESOLVED,
    BUDGET_EXHAUSTED;

    public static sealed abstract class SpikeException extends Exception permits
            FormatCorruptException, SemanticUnresolvedException, BudgetExhaustedException {
        private final List<Diagnostic> diagnostics;

        protected SpikeException(String message, List<Diagnostic> diagnostics) {
            super(message);
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        public abstract ImportFailure outcome();
    }

    public static final class FormatCorruptException extends SpikeException {
        public FormatCorruptException(String message, List<Diagnostic> diagnostics) {
            super(message, diagnostics);
        }

        @Override
        public ImportFailure outcome() {
            return FORMAT_CORRUPT;
        }
    }

    public static final class SemanticUnresolvedException extends SpikeException {
        public SemanticUnresolvedException(String message, List<Diagnostic> diagnostics) {
            super(message, diagnostics);
        }

        @Override
        public ImportFailure outcome() {
            return SEMANTIC_UNRESOLVED;
        }
    }

    public static final class BudgetExhaustedException extends SpikeException {
        public BudgetExhaustedException(String message, List<Diagnostic> diagnostics) {
            super(message, diagnostics);
        }

        @Override
        public ImportFailure outcome() {
            return BUDGET_EXHAUSTED;
        }
    }
}
