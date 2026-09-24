package neuro.spike.model;

/** One recording site. Coordinates carry an explicit basis ("probe" origin, um). */
public record ProbeSite(String siteId, double xUm, double yUm, String channel, String coordinateBasis) {
    public ProbeSite {
        if (siteId == null || siteId.isBlank()) throw new IllegalArgumentException("site id required");
        if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel required at " + siteId);
        if (coordinateBasis == null || coordinateBasis.isBlank()) {
            throw new IllegalArgumentException("coordinate basis required at " + siteId);
        }
    }
}
