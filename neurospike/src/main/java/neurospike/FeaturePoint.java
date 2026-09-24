package neurospike;

/** One point in the 2D feature projection, carrying stable identity. */
public record FeaturePoint(String detectionId, String clusterStableId, double x,
                           double y, double amplitude, String status) {
}
