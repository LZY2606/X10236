package org.neuroclust.model;

import java.util.List;

/**
 * 账本事件：所有计算结果记录输入摘要、参数版本、父级依赖。
 * 只追加，从不就地更新；“当前结论”由账本重放得到，旧事件可回到当时的依赖版本与可见结论。
 */
public record LedgerEvent(
        long seq,
        String eventId,
        String branch,
        String parentEventId,
        String kind,          // import | params_revision | source_append | window_recompute | verdict | invalidation | fork
        String createdAtIso,
        String summary,
        String parameterSetId,
        String featureSetId,
        String inputDigest,   // 规范化输入摘要 (sha256)
        List<String> parentDeps,
        List<String> affectedObjectIds,
        String status,        // ok | conflict | local_failure | budget_exhausted | format_corrupt | semantic_unresolvable
        String windowStartMs, // null 表示全程
        String windowEndMs,
        String payloadJson,   // 完整快照（派生结果/决定/诊断），时间旅行时直接读回
        String diagnosticJson
) {}
