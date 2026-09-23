package neurospike.model;

/** A positioned diagnostic. Never silently dropped; unconvertible values stay in place. */
public record Diag(String source, String position, String code, String message, String rawValue) {}
