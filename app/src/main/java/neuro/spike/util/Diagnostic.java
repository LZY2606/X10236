package neuro.spike.util;

/**
 * A located diagnostic attached to an input value that could not be converted.
 * The position is a JSON Pointer (RFC 6901) into the originating representation.
 */
public record Diagnostic(String code, String position, String message, String rawValue) {
}
