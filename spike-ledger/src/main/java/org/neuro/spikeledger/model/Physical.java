package org.neuro.spikeledger.model;

import java.util.Locale;

/**
 * 带单位的物理量：原始表示始终保留；只有派生层才规范化。
 * 无法转换的值保留在 {@code raw} 并通过 {@code convertError} 附诊断。
 */
public record Physical(double value, String unit, String raw, Double canonical,
                       String convertError) {

    public Physical {
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit required");
        }
    }

    public static Physical of(double value, String unit) {
        Double c = convert(value, unit);
        return new Physical(value, unit, format(value, unit), c, c == null ? "unknown unit: " + unit : null);
    }

    /** 保留无法转换的原始表示，附诊断，绝不丢值、绝不用默认值伪装。 */
    public static Physical raw(double value, String unit) {
        Double c = convert(value, unit);
        return new Physical(value, unit, format(value, unit), c,
                c == null ? "unknown unit: " + unit : null);
    }

    public static String format(double v, String unit) {
        return String.format(Locale.ROOT, "%.6g %s", v, unit);
    }

    /** 单位换算到规范化基准（mV、µm、ms）。返回 null 表示语义无解，留在原位。 */
    public static Double convert(double v, String unit) {
        return switch (unit.trim()) {
            case "mV" -> v;
            case "uV", "µV" -> v / 1000.0;
            case "V" -> v * 1000.0;
            case "um", "µm" -> v;
            case "mm" -> v * 1000.0;
            case "m" -> v * 1_000_000.0;
            case "ms" -> v;
            case "s" -> v * 1000.0;
            case "us", "µs" -> v / 1000.0;
            case "Hz" -> v;
            case "kHz" -> v * 1000.0;
            default -> null;
        };
    }

    public boolean convertible() {
        return canonical != null;
    }
}
