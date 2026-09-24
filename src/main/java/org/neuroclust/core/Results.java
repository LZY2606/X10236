package org.neuroclust.core;

import java.util.List;

/** 核心计算产物（纯数据，便于序列化与断言）。 */
public final class Results {
    private Results() {}

    public record Feature(String detectionId, double[] vector, List<String> names) {}

    /** 成员归属；candidates 多于一个表示无法唯一决定，保留全部合法候选（拆分后共享事件）。 */
    public record Membership(String detectionId, String primaryStableId, int primaryLabel,
                             List<String> candidateStableIds, String status) {}

    public record RefractoryPair(String a, String b, double dtMs, double refractoryMs) {}

    /** 幅度漂移证据；携带单位与时间基准，仅为证据，不改变稳定身份。 */
    public record AmplitudeDrift(String stableId, double slopeUvPerSec, double interceptUv,
                                 double firstMeanUv, double lastMeanUv, int nPoints,
                                 String amplitudeUnit, String timeBasis) {}

    /** 同时多通道传播（并发棘波）证据。 */
    public record PropagationGroup(String groupId, double anchorTimeMs, double spreadMs,
                                   double toleranceMs, List<String> channelIds,
                                   List<String> detectionIds, String timeBasis) {}

    public record ClusterView(String stableId, int label, List<String> detectionIds,
                              double[] centroid, double meanAmplitudeUv,
                              String primaryChannelId, int memberCount) {}

    /**
     * 一次派生：记录输入摘要、参数版本、特征版本、父级依赖、有效窗口、状态与证据。
     * status = ok | local_failure。窗口外簇保持父派生身份，不被重算。
     */
    public record Derivation(
            String derivationId,
            String parameterSetId, int paramsVersion,
            String featureSetId, int featureVersion,
            String windowStartMs, String windowEndMs, boolean fullRange,
            String inputDigest, List<String> parentDerivationIds,
            List<ClusterView> clusters,
            List<Membership> memberships,
            List<RefractoryPair> refractoryPairs,
            List<AmplitudeDrift> drifts,
            List<PropagationGroup> propagations,
            List<LineageEdge> lineage,
            String status, List<String> diagnostics
    ) {}

    /** 聚类身份谱系边（split/merge/continue/renumber）。 */
    public record LineageEdge(String childStableId, String parentStableId, String relation,
                              int oldLabel, int newLabel) {}

    /** 人工裁定（接受/否定/参数调整/合并/拆分）。冲突由账本状态机判定并保留双方。 */
    public record Verdict(String verdictId, String action, List<String> targetStableIds,
                          String decision, String rationale, List<String> evidenceRefs,
                          String createdAtIso, String status, String conflictWith) {}
}
