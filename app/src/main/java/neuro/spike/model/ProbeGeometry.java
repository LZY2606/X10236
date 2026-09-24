package neuro.spike.model;

import java.util.List;

/**
 * Immutable probe geometry. Channel coordinates have an explicit basis and units;
 * individual unconvertible coordinates stay null with diagnostics rather than shifting
 * other channels.
 */
public record ProbeGeometry(String id, String coordinateBasis, String pitchRaw,
                            List<ChannelSite> channels, List<neuro.spike.util.Diagnostic> diagnostics) {

    public record ChannelSite(int index, String label, Quantity x, Quantity y) {
    }
}
