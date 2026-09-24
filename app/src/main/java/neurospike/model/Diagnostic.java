package neurospike.model;

/**
 * Position-bearing diagnostic. Positions are JSON-path style locations relative
 * to the imported document, so an incomplete input never fails silently.
 */
public record Diagnostic(String path, String code, String message) {
    @Override
    public String toString() {
        return "[" + code + "] " + path + ": " + message;
    }
}
