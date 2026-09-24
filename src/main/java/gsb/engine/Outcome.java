package gsb.engine;

import gsb.model.Diagnostic;
import java.util.List;

/**
 * Result of a computation or import. The three failure modes are distinct:
 * resource budget exhausted, corrupt input format, semantically unsolvable.
 */
public sealed interface Outcome<T> {
    record Ok<T>(T value) implements Outcome<T> {}
    record BudgetExhausted<T>(long spent, long limit, List<Diagnostic> diagnostics) implements Outcome<T> {}
    record CorruptFormat<T>(List<Diagnostic> diagnostics) implements Outcome<T> {}
    record Unsolvable<T>(String reason, List<String> candidates, List<Diagnostic> diagnostics) implements Outcome<T> {}

    default boolean isOk() { return this instanceof Ok<T>; }
}
