package neuro.spike.model;

import java.util.*;

/** Immutable material layer: probe geometry. Kept separate from labels/decisions. */
public final class ProbeGeometry {
    private final String materialId;
    private final String sourceRef;
    private final String coordinateBasis;
    private final List<ProbeSite> sites;
    private final Map<String, ProbeSite> byChannel;

    public ProbeGeometry(String materialId, String sourceRef, String coordinateBasis, List<ProbeSite> sites) {
        this.materialId = Objects.requireNonNull(materialId);
        this.sourceRef = sourceRef;
        this.coordinateBasis = Objects.requireNonNull(coordinateBasis);
        this.sites = List.copyOf(sites);
        Map<String, ProbeSite> m = new HashMap<>();
        for (ProbeSite s : sites) {
            if (!s.coordinateBasis().equals(coordinateBasis)) {
                throw new IllegalArgumentException("mixed coordinate basis in geometry: "
                        + s.siteId() + " " + s.coordinateBasis() + " vs " + coordinateBasis);
            }
            if (m.put(s.channel(), s) != null) {
                throw new IllegalArgumentException("duplicate channel in geometry: " + s.channel());
            }
        }
        this.byChannel = Map.copyOf(m);
    }

    public String materialId() { return materialId; }
    public String sourceRef() { return sourceRef; }
    public String coordinateBasis() { return coordinateBasis; }
    public List<ProbeSite> sites() { return sites; }
    public Optional<ProbeSite> byChannel(String channel) { return Optional.ofNullable(byChannel.get(channel)); }
}
