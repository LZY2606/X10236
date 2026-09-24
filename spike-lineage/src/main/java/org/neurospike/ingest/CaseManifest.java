package org.neurospike.ingest;

import java.util.List;

/**
 * 领域案例包清单（脉冲簇谱）。这不是通用表格：每个段都指向具体的
 * 神经电生理材料文件（几何/波形/检测事件/阈值/特征/标签），并声明单位基准。
 */
public record CaseManifest(String caseId, String title, String probeId,
                          CoordinateBasis coordinateBasis, TimeBasis timeBasis,
                          Files files, List<ReplayStep> replay) {

    public record CoordinateBasis(String unit, String origin) {}
    public record TimeBasis(String t0IsoUtc, double sampleRateHz, String rateRaw, String rateUnit) {}
    public record Files(List<GeometryRow> geometry, String eventsCsv, String thresholdsCsv,
                        List<FeatureSetFile> featureSets, List<LabelFile> labels) {}
    public record GeometryRow(int localIndex, String label, double x, double y) {}
    public record FeatureSetFile(String featureSetId, int version, String algorithm,
                                 String paramsCsv, String createdAt) {}
    public record LabelFile(String labelSetId, int version, String parentLabelSetId,
                            String labelsCsv, String note, String createdAt) {}

    /** 机器可读取的重放步骤：审阅者从最终状态反查材料、规则版本与决定。 */
    public record ReplayStep(String stepId, String action, String target,
                             String expect, String affectsScope) {}
}
