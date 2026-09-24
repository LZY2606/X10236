package ephys;

import java.util.List;

/** Computation result. Resource-budget exhaustion, corrupt input format and
 *  semantic unsolvability are three DISTINCT results, never collapsed into
 *  nulls or defaults. */
public sealed interface Outcome<T> {

    record Ok<T>(T value, List<Diag> diags) implements Outcome<T> {}

    record BudgetExhausted<T>(String detail, List<Diag> diags) implements Outcome<T> {}

    record CorruptFormat<T>(List<Diag> diags) implements Outcome<T> {}

    record SemanticUnsolvable<T>(List<Diag> diags) implements Outcome<T> {}

    default boolean isOk() {
        return this instanceof Ok<T>;
    }

    default String kind() {
        if (this instanceof Ok<T>) return "ok";
        if (this instanceof BudgetExhausted<T>) return "budget_exhausted";
        if (this instanceof CorruptFormat<T>) return "corrupt_format";
        return "semantic_unsolvable";
    }

    @SuppressWarnings("unchecked")
    default List<Diag> diags() {
        if (this instanceof Ok<T> o) return o.diags();
        if (this instanceof BudgetExhausted<T> b) return b.diags();
        if (this instanceof CorruptFormat<T> c) return c.diags();
        if (this instanceof SemanticUnsolvable<T> s) return s.diags();
        return List.of();
    }
}
