package com.neurogsb.core;

/** A positioned diagnostic. Position is a JSON pointer or line:column, never blank. */
public record Diagnostic(String position, String code, String message) {
    public String toJson() {
        return Json.write(this);
    }
}
