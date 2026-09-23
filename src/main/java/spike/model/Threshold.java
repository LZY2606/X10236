package spike.model;

/**
 * An immutable detection-threshold snapshot for one channel. Thresholds are versioned material:
 * a parameter revision inserts a new row rather than mutating an old one.
 */
public record Threshold(String id, String channelId, Quantity value, String method,
                        String basis, String version) {
}
