package neurogsb.model;

import java.util.List;

/** 三种失败必须可区分：预算耗尽 / 格式损坏 / 语义无解。 */
public sealed interface OpResult {
    record Ok(String summary, List<String> affected) implements OpResult {}
    record BudgetExhausted(long required, long budget, String stage) implements OpResult {}
    record FormatCorrupt(String position, String detail) implements OpResult {}
    record SemanticUnsolvable(String position, String detail) implements OpResult {}

    default boolean ok() { return this instanceof Ok; }
}
