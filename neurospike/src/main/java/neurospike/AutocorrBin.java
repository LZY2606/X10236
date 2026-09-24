package neurospike;

/** One autocorrelation lag bin (positive lags both directions). */
public record AutocorrBin(String clusterStableId, double lagSeconds, int count,
                          String unit) {
}
