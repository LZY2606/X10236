package org.neurospike.domain;

import java.util.List;

public final class DomainExceptions {
    private DomainExceptions() {
    }

    public static class InputIncompleteException extends Exception {
        private final transient List<Diagnostic> diagnostics;

        public InputIncompleteException(List<Diagnostic> diagnostics) {
            super("input is incomplete");
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static class CorruptFormatException extends Exception {
        private final transient List<Diagnostic> diagnostics;

        public CorruptFormatException(String message, List<Diagnostic> diagnostics) {
            super(message);
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static class SemanticUnresolvableException extends Exception {
        private final transient List<Diagnostic> diagnostics;

        public SemanticUnresolvableException(String message, List<Diagnostic> diagnostics) {
            super(message);
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }

    public static class BudgetExhaustedException extends Exception {
        private final int budget;
        private final int observed;

        public BudgetExhaustedException(int budget, int observed) {
            super("resource budget exhausted after " + observed + " of " + budget + " permitted records");
            this.budget = budget;
            this.observed = observed;
        }

        public int budget() {
            return budget;
        }

        public int observed() {
            return observed;
        }
    }
}
