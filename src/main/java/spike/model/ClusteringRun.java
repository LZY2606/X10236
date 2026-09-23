package spike.model;

/**
 * A deterministic clustering computation. Every result records its input digest, the parameter
 * version (feature version + threshold version + clustering params) and the time window it is
 * valid for, so a result can always be traced to exact inputs.
 */
public record ClusteringRun(String id, String featureVersionId, String thresholdVersion,
                            String paramsVersion, double windowStartSec, double windowEndSec,
                            String inputDigest, long sinceSeq, Long untilSeq, String status,
                            String note) {
}
