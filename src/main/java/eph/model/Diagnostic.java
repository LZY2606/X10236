package eph.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** A located, machine-readable problem. Never silently replaced by a default value. */
public final class Diagnostic {
    public final String code;
    public final String path;
    public final String message;
    public final String rawValue;

    public Diagnostic(String code, String path, String message, String rawValue) {
        this.code = code;
        this.path = path;
        this.message = message;
        this.rawValue = rawValue;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("path", path);
        m.put("message", message);
        if (rawValue != null) m.put("rawValue", rawValue);
        return m;
    }

    public static Diagnostic of(String code, String path, String message) {
        return new Diagnostic(code, path, message, null);
    }
}
