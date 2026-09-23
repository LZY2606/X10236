package org.neurospike.ingest;

import org.neurospike.domain.Models.Quant;

/**
 * Parses "12.34 us" style tokens. The raw representation is always retained; values that
 * cannot be converted stay in place with a diagnostic instead of becoming null/default.
 */
public final class QuantParser {
    private QuantParser() {
    }

    public static Quant parse(String token, String expectedUnit, String reference) {
        String raw = token == null ? "" : token.trim();
        String diagnostic = null;
        if (raw.isEmpty()) {
            return new Quant(raw, null, expectedUnit == null ? "" : expectedUnit, reference, false,
                    "empty value retained at source");
        }
        String unit = expectedUnit;
        String numberPart = raw;
        int sp = lastIndexOfUnitSep(raw);
        if (sp > 0) {
            numberPart = raw.substring(0, sp).trim();
            unit = raw.substring(sp + 1).trim();
        }
        double value;
        try {
            value = Double.parseDouble(numberPart);
        } catch (NumberFormatException e) {
            return new Quant(raw, null, unit == null ? "" : unit, reference, false,
                    DiagnosticBuilder.unconvertible(raw,
                            "cannot convert '" + numberPart + "' to " + (expectedUnit == null ? "number" : expectedUnit)));
        }
        boolean convertible = true;
        if (expectedUnit != null && unit != null && !unit.isBlank() && !unit.equals(expectedUnit)) {
            convertible = false;
            diagnostic = DiagnosticBuilder.unconvertible(raw,
                    "unit '" + unit + "' cannot be normalized to '" + expectedUnit + "'; original retained");
        }
        return new Quant(raw, value, unit == null ? "" : unit, reference, convertible, diagnostic);
    }

    private static int lastIndexOfUnitSep(String raw) {
        for (int i = raw.length() - 1; i >= 0; i--) {
            char ch = raw.charAt(i);
            if (Character.isLetter(ch) || ch == 0x00B5) {
                continue;
            }
            if (ch == ' ') {
                return i;
            }
            return -1;
        }
        return -1;
    }
}
