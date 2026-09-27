package app.model;

import java.util.List;
import java.util.Map;

/** 不可变材料层：一旦记录即不改写；修订以新版本追加。 */
public final class Materials {
    private Materials() {}

    /** 原始波形引用与片段（引用而非内联波形本身）。 */
    public record WaveformRef(String id, int revision, String sourceUri, String checksum,
                              String channelId, Qty sampleRate, Qty duration,
                              List<Double> snippetSamples, String sampleUnit,
                              String timeBasis, boolean valid, String diagnostic) {}

    /** 探针几何：通道 → 三维坐标（µm），保留原始坐标表示。 */
    public record ProbeGeometry(String id, int revision, String probeName,
                                Map<String, Qty> xUm, Map<String, Qty> yUm,
                                Map<String, Qty> zUm, String coordinateBasis,
                                boolean valid, String diagnostic) {}

    /** 检测阈值：逐通道、保留原始阈值表示与其方法。 */
    public record DetectionThreshold(String id, int revision, String channelId,
                                     Qty threshold, String method,
                                     String detectorVersion,
                                     boolean valid, String diagnostic) {}

    /** 特征版本：算法/参数版本化的特征投影结果。 */
    public record FeatureVersion(String id, int revision, String algorithm,
                                 String version, Map<String, String> params,
                                 String inputSummary,
                                 Map<String, double[]> coordinates,
                                 List<String> coordinateBases,
                                 boolean valid, String diagnostic) {}

    /** 聚类标签：某次运行给检测事件分配的簇编号（标签可变，非稳定身份）。 */
    public record ClusterLabel(String id, int revision, String featureVersionId,
                               String runId, Map<String, Integer> labelByEvent,
                               Map<String, String> labelBasis,
                               boolean valid, String diagnostic) {}

    /** 裁定事件：人工决定，独立成层。 */
    public record AdjudicationEvent(String id, int revision, String decision,
                                    String scope, List<String> targetIds,
                                    String rationale, String basis,
                                    String parentDecisionId,
                                    boolean conflictsWith, String conflictDetail,
                                    boolean valid, String diagnostic) {}

    /** 多通道检测事件（棘波候选）。 */
    public record SpikeEvent(String id, int revision, String channelId,
                             Qty timeMs, Qty amplitudeUv, String waveformRefId,
                             Qty peakToTroughMs, boolean concurrent,
                             boolean valid, String diagnostic) {}
}
