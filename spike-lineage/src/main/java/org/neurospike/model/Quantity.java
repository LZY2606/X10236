package org.neurospike.model;

import java.util.Objects;

/**
 * 带单位的领域量。原始表示始终保留（raw + rawUnit）；只有能够按已知换算表
 * 转换时才填写规范化值（siValue + siUnit）。无法转换时 siValue/siUnit 为 null，
 * 并在诊断中说明，绝不丢弃原始值。
 */
public final class Quantity {
    private final String raw;
    private final String rawUnit;
    private final Double siValue;
    private final String siUnit;

    public Quantity(String raw, String rawUnit, Double siValue, String siUnit) {
        this.raw = raw;
        this.rawUnit = rawUnit;
        this.siValue = siValue;
        this.siUnit = siUnit;
    }

    public String raw() { return raw; }
    public String rawUnit() { return rawUnit; }
    public Double siValue() { return siValue; }
    public String siUnit() { return siUnit; }
    public boolean convertible() { return siValue != null; }

    /** 解析形如 "12.5 uV" 的领域量；无法转换时保留原位并带诊断。 */
    public static ParseResult parse(String raw, String rawUnit, String targetSiUnit) {
        Objects.requireNonNull(raw, "raw quantity value");
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return new ParseResult(new Quantity(raw, rawUnit, null, targetSiUnit),
                    "quantity-not-numeric: '" + raw + "' (" + rawUnit + ")");
        }
        Double factor = UnitTable.toSi(rawUnit, targetSiUnit);
        if (factor == null) {
            return new ParseResult(new Quantity(raw, rawUnit, null, targetSiUnit),
                    "unit-unconvertible: '" + rawUnit + "' -> '" + targetSiUnit + "'");
        }
        return new ParseResult(new Quantity(raw, rawUnit, value * factor, targetSiUnit), null);
    }

    public record ParseResult(Quantity quantity, String diagnostic) {
        public boolean ok() { return diagnostic == null; }
    }

    @Override
    public String toString() {
        return raw + " " + rawUnit + (convertible() ? " [" + siValue + " " + siUnit + "]" : " [UNCONVERTED]");
    }
}
