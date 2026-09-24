package neurospike;

/** One firing-rate bin. Endpoints use the half-open [start,end) convention. */
public record RateBin(String clusterStableId, double startSeconds, double endSeconds,
                      int count, double rateHz, boolean endpointStart,
                      boolean endpointEnd, String unit) {
}
