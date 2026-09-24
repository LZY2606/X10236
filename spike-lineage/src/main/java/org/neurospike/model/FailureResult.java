package org.neurospike.model;

import java.util.List;

/** 与成功结果区分的结构化失败，携带失败类别与带位置诊断。 */
public record FailureResult(FailureType type, List<Diagnostic> diagnostics) {
    public static FailureResult of(FailureType type, List<Diagnostic> diagnostics) {
        return new FailureResult(type, List.copyOf(diagnostics));
    }
}
