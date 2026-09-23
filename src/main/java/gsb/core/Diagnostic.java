package gsb.core;

/** A located diagnostic. Unconvertible values stay in place (rawValue) and the
 *  diagnostic points at where they live; nothing is silently defaulted. */
public record Diagnostic(String stage, String severity, String location, String message, String rawValue) {
    public static Diagnostic error(String stage, String location, String message, String rawValue) {
        return new Diagnostic(stage, "ERROR", location, message, rawValue);
    }
    public static Diagnostic warn(String stage, String location, String message, String rawValue) {
        return new Diagnostic(stage, "WARN", location, message, rawValue);
    }
}
