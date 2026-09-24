package neurospike;

import java.util.List;

/**
 * A detected spike candidate. {@code sample} is relative to the recording time
 * basis; {@code peakAmplitude} keeps the original representation.
 */
public record Detection(String detectionId, String channelId, Quant sample,
                        Quant peakAmplitude, String waveformId,
                        List<String> concurrentWith, List<String> propagatedTo,
                        String status, String diagnostic) {

    public boolean active() {
        return status == null || "OK".equals(status);
    }
}
