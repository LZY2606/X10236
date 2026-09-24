package spike.core;

import java.util.Objects;

/** A located diagnostic. Failures never masquerade as empty/default values. */
public final class Diagnostic {
    private final String severity;
    private final String code;
    private final String materialId;
    private final String path;
    private final String message;
    private final String rawValue;

    public Diagnostic(String severity, String code, String materialId, String path, String message, String rawValue) {
        this.severity = Objects.requireNonNull(severity);
        this.code = Objects.requireNonNull(code);
        this.materialId = materialId;
        this.path = path;
        this.message = Objects.requireNonNull(message);
        this.rawValue = rawValue;
    }

    public static Diagnostic error(String code, String materialId, String path, String message, String rawValue) {
        return new Diagnostic("ERROR", code, materialId, path, message, rawValue);
    }

    public static Diagnostic warn(String code, String materialId, String path, String message, String rawValue) {
        return new Diagnostic("WARN", code, materialId, path, message, rawValue);
    }

    public String severity() { return severity; }
    public String code() { return code; }
    public String materialId() { return materialId; }
    public String path() { return path; }
    public String message() { return message; }
    public String rawValue() { return rawValue; }
}
