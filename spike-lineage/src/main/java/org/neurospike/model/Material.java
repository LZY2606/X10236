package org.neurospike.model;

import java.util.List;

/** 不可变材料层的领域对象。所有字段在导入后不可修改；修订以新版本追加。 */
public final class Material {

    /** 探针通道几何：坐标带长度单位基准。 */
    public record ProbeChannel(String probeId, int localIndex, String label,
                               double x, double y, String coordUnit) {
        public String stableId() { return "ch_" + probeId + "_" + localIndex; }
    }

    /**
     * 原始波形片段（内容寻址）。bytes 为采样原始整数表示；
     * scale/offset 给出原始 ADC 值 -> 物理量的换算（gain 带单位）。
     */
    public record Waveform(String waveformId, int channelLocalIndex, int sampleCount,
                           String sampleRateRaw, String sampleRateUnit,
                           double sampleRateHz, int samplesPerSpike,
                           double gainToUnit, String physicalUnit,
                           byte[] samples, String sha256) {
        public double[] physicalMicrovolts() {
            double factor = UnitTable.toSi(physicalUnit, "uV") == null ? 1.0
                    : UnitTable.toSi(physicalUnit, "uV");
            double[] out = new double[samples.length];
            for (int i = 0; i < samples.length; i++) {
                out[i] = samples[i] * gainToUnit * factor;
            }
            return out;
        }
    }

    /** 检测阈值版本（每个通道一条），带判定算子。 */
    public record Threshold(String thresholdId, int version, String probeId,
                            int channelLocalIndex, String operator,
                            Quantity value, String basis, String createdAt) {}

    /** 特征提取版本（参数集）。 */
    public record FeatureVersion(String featureSetId, int version, String algorithm,
                                 String paramsCanonical, String createdAt) {}

    /**
     * 多通道检测事件。时间单位明确（秒，相对于记录起点 t0）；
     * amplitude 保留原始表示；featureVector 对应某个特征版本。
     */
    public record SpikeEvent(String eventId, double timeSeconds, int channelLocalIndex,
                             String waveformId, Quantity amplitude,
                             String featureSetId, List<Double> features,
                             String rawLine, boolean valid, String invalidReason,
                             Integer supersedesOrder) {
        public SpikeEvent {
            features = features == null ? List.of() : List.copyOf(features);
        }
    }

    /** 聚类标签集版本。 */
    public record LabelSet(String labelSetId, int version, String parentLabelSetId,
                           String note, String createdAt) {}

    /** 一条聚类指派：event 属于 cluster。cluster 身份由成员事件集合内容决定。 */
    public record ClusterAssignment(String labelSetId, String clusterKey,
                                    int displayNumber, String eventId) {}

    /** 人工裁定事件（只追加）。 */
    public record Adjudication(String adjudicationId, long seq, String branch,
                               String kind, String nodeId, String payload,
                               String createdAt) {}

    /** 分支指针（main / review-*）。 */
    public record Branch(String name, long headSeq) {}
}
