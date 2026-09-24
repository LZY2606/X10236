package org.neurospike.model;

/** 带位置的诊断：输入不完整/不可转换时返回它，绝不用空值伪装成功。 */
public record Diagnostic(String code, String path, String message) {
    public static Diagnostic of(String code, String path, String message) {
        return new Diagnostic(code, path, message);
    }
    @Override public String toString() { return code + " @ " + path + ": " + message; }
}
