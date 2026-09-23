package eph.model;

import java.util.Collections;
import java.util.List;

/**
 * Three deliberately distinct terminal outcomes required by the specification:
 * success, resource budget exhausted, corrupt format, and semantic impossibility.
 */
public sealed interface Outcome<T> permits Outcome.Ok, Outcome.BudgetExhausted, Outcome.Corrupt, Outcome.SemanticallyInvalid {

    default boolean ok() {
        return this instanceof Ok<T>;
    }

    record Ok<T>(T value) implements Outcome<T> {
    }

    record BudgetExhausted<T>(String what, long limit, long used, List<Diagnostic> diagnostics) implements Outcome<T> {
        public BudgetExhausted {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    record Corrupt<T>(List<Diagnostic> diagnostics) implements Outcome<T> {
        public Corrupt {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    record SemanticallyInvalid<T>(List<Diagnostic> diagnostics) implements Outcome<T> {
        public SemanticallyInvalid {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean hasCode(String code) {
            return diagnostics.stream().anyMatch(d -> d.code.equals(code));
        }
    }

    static <T> Ok<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> BudgetExhausted<T> budget(String what, long limit, long used) {
        return new BudgetExhausted<>(what, limit, used, Collections.emptyList());
    }

    static <T> Corrupt<T> corrupt(List<Diagnostic> diagnostics) {
        return new Corrupt<>(diagnostics);
    }

    static <T> SemanticallyInvalid<T> semantic(List<Diagnostic> diagnostics) {
        return new SemanticallyInvalid<>(diagnostics);
    }
}
