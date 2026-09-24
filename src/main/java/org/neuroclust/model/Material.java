package org.neuroclust.model;

import java.util.List;

/**
 * 不可变材料层（immutable material）。
 * 波形引用 / 探针几何 / 检测阈值 / 特征版本 / 聚类标签 / 裁定事件 分开存储。
 * 源数据改动通过追加批次 + 失效事件表达，绝不就地覆盖原始材料。
 */
public final class Material {
    private Material() {}

    public record Channel(String channelId, String label, Types.Coordinate position,
                          Types.Quantity gain, String rawJson) {}

    public record ProbeGeometry(String probeId, String model, String coordinateBasis,
                                List<Channel> channels) {}

    /** 原始波形引用：只存引用与采样率/基准，不把派生波形冒充原始材料。 */
    public record WaveformRef(String waveformId, String detectionId, String uri,
                              Types.Quantity samplingRate, int sampleCount,
                              String unit, String checksum, String rawJson) {}

    /** 检测阈值（按通道），分开存储；rawJson 保留导入表示。 */
    public record Threshold(String channelId, Types.Quantity value, String rule,
                            String parameterSetId, String rawJson) {}

    public record FeatureVersion(String featureSetId, int version, String algorithm,
                                 List<String> featureNames, String distanceMetric,
                                 String paramsJson, String rawJson) {}

    public record ClusteringParams(String parameterSetId, int version, String algorithm,
                                   double radius, String distanceMetric,
                                   double refractoryMs, double concurrencyToleranceMs,
                                   String paramsJson, String rawJson) {}

    /** 多通道检测事件；时间为相对会话起点的毫秒偏移。 */
    public record Detection(String detectionId, String sourceBatch, double timeMs,
                            String channelId, Types.Quantity amplitude,
                            Types.Quantity peakSnr, String waveformId,
                            String rawJson, List<Types.Diagnostic> diagnostics) {}

    public record ProjectMeta(String caseId, String title, Types.TimeRef timeRef,
                              String schemaVersion, String createdAtIso) {}

    /**
     * 初始案例包：材料 + 预置重放步骤 + 初始参数/特征版本。
     * steps 实际驱动引擎经过全部边界情形。
     */
    public record CasePackage(ProjectMeta meta, ProbeGeometry probe,
                              List<WaveformRef> waveforms, List<Threshold> thresholds,
                              List<FeatureVersion> featureVersions,
                              ClusteringParams initialParams,
                              List<Detection> detections,
                              List<String> replayStepIds,
                              List<ReplayStep> steps, String rawJson) {}

    /** 重放步骤的声明式描述（案例包的一部分，离线自带）。 */
    public record ReplayStep(String stepId, String kind, String title, String narrative,
                             String paramsJson, String expectsJson) {}
}
