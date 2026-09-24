package org.neurospike.model;

import java.util.List;

/** 派生层：每个结果记录输入摘要、参数版本与父级依赖，构成可回溯 DAG。 */
public final class Derived {

    public enum NodeKind {
        DETECTION_SUMMARY, CONCURRENT_GROUP, REFRACTORY_EVIDENCE,
        AMPLITUDE_DRIFT, MULTICHANNEL_PROPAGATION, CLUSTER_VIEW,
        RATE_VIEW, AUTOCORRELATION, PROJECTION, LINEAGE_LINK, LOCAL_FAILURE
    }

    /**
     * 派生节点。id 由 (kind, 输入摘要, 参数版本, 父级集合) 内容寻址；
     * payload 为 JSON；status = OK / EVIDENCE / LOCAL_FAILURE / AMBIGUOUS。
     */
    public record Node(String nodeId, NodeKind kind, String status,
                       String inputDigest, String paramVersion,
                       String payloadJson, List<String> parentIds,
                       long birthDecisionSeq, boolean retired) {
        public Node { parentIds = parentIds == null ? List.of() : List.copyOf(parentIds); }
    }

    /** 时间窗（半开区间 [startSeconds, endSeconds)），修改时只有相交簇重算。 */
    public record TimeWindow(double startSeconds, double endSeconds) {
        public boolean contains(double t) { return t >= startSeconds && t < endSeconds; }
        public boolean intersectsCluster(double minT, double maxT) {
            return minT < endSeconds && maxT >= startSeconds;
        }
    }

    /** 合并/拆分/重编号谱系边。 */
    public record LineageEdge(String edgeId, String relation,
                              String fromClusterKey, String toClusterKey,
                              int fromLabelVersion, int toLabelVersion,
                              String sharedEventId, String evidenceNodeId) {}

    /** 两个事件的并发（绝对时间差 <= tolSeconds），仅作为证据。 */
    public record ConcurrentPair(String eventIdA, String eventIdB,
                                 double dtSeconds, int channelA, int channelB) {}

    /** 不应期违例证据（同簇内 ISI < refractorySeconds）。 */
    public record RefractoryViolation(String clusterKey, String eventIdA,
                                      String eventIdB, double isiSeconds,
                                      double refractorySeconds) {}

    /** 幅度漂移证据（分箱中位幅度斜率显著）。 */
    public record AmplitudeDrift(String clusterKey, double earlyMedianUv,
                                 double lateMedianUv, double slopeUvPerMin,
                                 double significance) {}

    /** 多通道同时传播证据（同组并发跨越 >=2 通道）。 */
    public record MultichannelPropagation(String groupId, int channelCount,
                                          double spanSeconds, List<String> eventIds) {}
}
