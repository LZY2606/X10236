package org.neuroclust.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目内存聚合根：不可变材料（追加式）+ 可修改决定（追加式事件账本）。
 * 该类不做业务判定，只承载状态；业务规则在 core 包，可被测试直接调用。
 */
public final class Project {
    public final Material.ProjectMeta meta;
    public final Map<String, Material.ProbeGeometry> probes = new LinkedHashMap<>();
    public final Map<String, Material.WaveformRef> waveforms = new LinkedHashMap<>();
    public final Map<String, Material.Threshold> thresholds = new LinkedHashMap<>();
    public final Map<String, Material.FeatureVersion> featureVersions = new LinkedHashMap<>();
    public final Map<String, Material.ClusteringParams> paramSets = new LinkedHashMap<>();
    public final Map<String, Material.Detection> detections = new LinkedHashMap<>();

    /** 追加式账本事件（计算/导入/裁定/失效/分叉）。 */
    public final List<LedgerEvent> events = new ArrayList<>();

    public Project(Material.ProjectMeta meta) {
        this.meta = meta;
    }

    public long seqOf(String eventId) {
        for (LedgerEvent e : events) if (e.eventId.equals(eventId)) return e.seq;
        return -1;
    }
}
