package gsb.model;

/** A positioned diagnostic. Never silently substitute nulls/defaults for missing input. */
public record Diagnostic(String location, String message) {
    @Override public String toString() { return location + ": " + message; }
}
