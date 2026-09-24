package org.neuro.spikeledger.model;

import java.util.List;

/**
 * 三种必须可区分的结果：资源预算耗尽 / 格式损坏 / 语义无解；
 * 成功路径另列。任何失败都不得用空值或默认值伪装成功。
 */
public record ImportResult(Outcome outcome, String bundleId, int acceptedCount,
                           List<Diagnostic> diagnostics, List<String> acceptedMaterialIds) {

    public enum Outcome { SUCCESS, FORMAT_CORRUPTED, SEMANTIC_UNRESOLVABLE, BUDGET_EXHAUSTED }

    public boolean ok() {
        return outcome == Outcome.SUCCESS;
    }
}
