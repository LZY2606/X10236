package neurospike;

import java.util.List;

/**
 * One piece of structured evidence. Findings are evidence only - never stable
 * identities. {@code validFromEvent}/{@code validToEvent} bound the validity
 * interval; {@code status} is VALID or LOCAL_INVALID.
 */
public record Finding(String findingId, String type, String clusterStableId,
                      List<String> detectionIds, String value, String unit,
                      String basis, String status, String invalidReason,
                      String introducedByEvent, String validFromEvent,
                      String validToEvent, String detailJson, String findingKind) {

    public static final String REFRACTORY_VIOLATION = "REFRACTORY_VIOLATION";
    public static final String AMPLITUDE_DRIFT = "AMPLITUDE_DRIFT";
    public static final String CONCURRENT_SPIKE = "CONCURRENT_SPIKE";
    public static final String MULTICHANNEL_PROPAGATION = "MULTICHANNEL_PROPAGATION";
    public static final String SPLIT_SHARED_EVENT = "SPLIT_SHARED_EVENT";
    public static final String WINDOW_ENDPOINT = "WINDOW_ENDPOINT";
    public static final String LOCAL_VALUE_INVALID = "LOCAL_VALUE_INVALID";
    public static final String AUTOCORRELATION = "AUTOCORRELATION";
    public static final String FIRE_RATE = "FIRE_RATE";

    public static final String VALID = "VALID";
    public static final String LOCAL_INVALID = "LOCAL_INVALID";
}
