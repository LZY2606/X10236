package org.neurospike.ingest;

import org.neurospike.model.Diagnostic;
import org.neurospike.model.FailureResult;

import java.util.List;

/** 导入结果：成功（可能仍带保留原位的行级诊断）或三种失败之一。 */
public record IngestResult(boolean success, int importedEvents, int retainedRows,
                           List<Diagnostic> warnings, FailureResult failure) {
    public static IngestResult ok(int events, int retained, List<Diagnostic> warnings) {
        return new IngestResult(true, events, retained, List.copyOf(warnings), null);
    }
    public static IngestResult failed(FailureResult failure) {
        return new IngestResult(false, 0, 0, List.of(), failure);
    }
}
