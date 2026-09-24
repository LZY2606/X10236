package neurospike;

/** One electrode site on the probe. Coordinates use {@code coordinateBasis} from Probe. */
public record ProbeSite(String siteId, String channelId, Quant x, Quant y, Quant z) {
}
