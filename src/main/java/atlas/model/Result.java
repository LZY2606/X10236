package atlas.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 计算/导入结果。资源预算耗尽、格式损坏、语义无解是三种不同的失败状态，
 * 诊断必须带位置（path），不允许用空值或默认值伪装成功。
 */
public final class Result<T> {
    public enum Status { OK, BUDGET_EXHAUSTED, CORRUPT_FORMAT, SEMANTIC_UNSOLVABLE }

    public record Diagnostic(String path, String message) {}

    public final Status status;
    public final T value;
    public final List<Diagnostic> diagnostics;

    private Result(Status status, T value, List<Diagnostic> diagnostics) {
        this.status = status;
        this.value = value;
        this.diagnostics = Collections.unmodifiableList(diagnostics);
    }

    public static <T> Result<T> ok(T value) {
        return new Result<>(Status.OK, value, List.of());
    }

    public static <T> Result<T> ok(T value, List<Diagnostic> diags) {
        return new Result<>(Status.OK, value, diags);
    }

    public static <T> Result<T> fail(Status status, String path, String message) {
        if (status == Status.OK) throw new IllegalArgumentException("fail requires non-OK status");
        return new Result<>(status, null, List.of(new Diagnostic(path, message)));
    }

    public static <T> Result<T> fail(Status status, List<Diagnostic> diags) {
        if (status == Status.OK) throw new IllegalArgumentException("fail requires non-OK status");
        return new Result<>(status, null, diags);
    }

    public boolean isOk() { return status == Status.OK; }

    public static List<Diagnostic> diags() { return new ArrayList<>(); }
}
