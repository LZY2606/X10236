package gsb.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The three failure modes are deliberately different types so callers (and tests) can
 * distinguish budget exhaustion, corrupt bytes, and semantically impossible input.
 */
public final class Errors {
    private Errors() {}

    /** Resource budget exhausted: input is well formed but exceeds the declared allowance. */
    public static final class BudgetExhaustedException extends RuntimeException {
        public BudgetExhaustedException(String message) {
            super(message);
        }
    }

    /** Format corruption: bytes cannot be parsed at a concrete position. */
    public static final class FormatCorruptException extends RuntimeException {
        private final String position;

        public FormatCorruptException(String message, String position) {
            super(message + " @ " + position);
            this.position = position;
        }

        public String position() {
            return position;
        }
    }

    /** Semantic impossibility / ambiguity: parsed input has no consistent interpretation. */
    public static final class SemanticImpossibleException extends RuntimeException {
        private final List<Model.Diagnostic> diagnostics;

        public SemanticImpossibleException(String message, List<Model.Diagnostic> diagnostics) {
            super(message);
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        public List<Model.Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
