package ephys.model;

/** A positioned diagnostic: never silently substitute nulls/defaults for broken input. */
public record Diagnostic(String file, int record, String field, String message) {
    public String format() {
        return file + "#" + record + (field == null ? "" : "." + field) + ": " + message;
    }
}
