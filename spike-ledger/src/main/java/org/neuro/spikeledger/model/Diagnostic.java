package org.neuro.spikeledger.model;

/** 带位置的诊断：指向材料 id / 记录行 / 字段路径。 */
public record Diagnostic(String severity, String code, String location, String message) {

    public static Diagnostic error(String code, String location, String message) {
        return new Diagnostic("error", code, location, message);
    }

    public static Diagnostic warn(String code, String location, String message) {
        return new Diagnostic("warn", code, location, message);
    }

    public String canonical() {
        return severity + "|" + code + "|" + location + "|" + message;
    }
}
