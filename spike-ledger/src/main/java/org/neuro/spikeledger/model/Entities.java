package org.neuro.spikeledger.model;

import java.util.List;

/** 领域实体：全部不可变；原始层保留原始表示（raw/unit），不做就地规范化。 */
public final class Entities {
    private Entities() {}

    /** 原始波形引用：不内联采样数据，引用波形包文件与片段区间。 */
    public record RawWaveformRef(String id, String recordingId, int channel,
                                 String uri, String sampleUnit, double sampleRateHz,
                                 long startSample, long endSample,
                                 String timeBasis, String checksumSha256) {}

    /** 探针几何（通道坐标）。channels: [channel,x,y]。 */
    public record ProbeGeometry(String id, String revision, CoordBasis basis,
                                double[][] channels, String rawRepresentation) {}

    /** 检测阈值：方法、阈值量（带单位）与适用通道。 */
    public record DetectionThreshold(String id, String method, Physical level,
                                     List<Integer> channels, String versionTag) {}

    /** 多通道检测事件（棘波候选）。 */
    public record SpikeEvent(String id, String recordingId, double timeMs,
                             String timeBasis, int channel, Physical amplitude,
                             String waveformRefId, Long concurrencyGroup,
                             String sourceLine) {}

    public record FeatureVersion(String id, String name, int version,
                                 List<String> featureNames, String paramsJson) {}

    public record FeatureVector(String eventId, String featureVersionId,
                                double[] values, List<String> units) {}

    /** 一次聚类运行：固定输入版本 + 参数版本 + 时间窗（端点为闭/开区间）。 */
    public record ClusteringRun(String id, String featureVersionId, String thresholdId,
                                String geometryId, String paramsVersion,
                                double windowStartMs, double windowEndMs,
                                boolean endInclusive, String inputDigest) {}

    /** 聚类标签：聚类编号只是运行内整数，不是稳定身份；同一事件可被多个运行引用（拆分后共享）。 */
    public record ClusterLabel(String runId, String eventId, int labelIndex,
                               double membership) {}

    /** 人工裁定事件：独立于聚类标签存储。decision ∈ ACCEPT,REJECT,MERGE,SPLIT,PARAM_ADJUST,CONFLICT。 */
    public record Adjudication(String id, String branchId, String parentEventId,
                               String targetUnitId, String targetEventId,
                               String decision, String rationale,
                               String paramsVersion, long createdAtMs,
                               String conflictWithEventId) {}

    /** 派生稳定单元：跨重编号的稳定身份 u_…，编号 labelIndex 仅作显示。 */
    public record Unit(String id, String branchId, String runId, int labelIndex,
                       List<String> eventIds, double windowStartMs, double windowEndMs) {}
}
