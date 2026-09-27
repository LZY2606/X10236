package app.model;

import java.util.Objects;

/**
 * 带单位或坐标基准的领域量。
 * 导入层保留原始表示；规范化只发生在派生层。
 * 无法转换时保留原值并附诊断，而不是丢成空值/默认值。
 */
public final class Qty {
    public final String raw;        // 原始表示，逐字保留
    public final double value;      // 规范化后的数值
    public final String unit;       // 规范化单位
    public final String basis;      // 坐标/时间基准，如 "probe-A/um"、"recordStart/ms"
    public final boolean valid;
    public final String diagnostic; // 无效原因；有效时为 null

    private Qty(String raw, double value, String unit, String basis,
                boolean valid, String diagnostic) {
        this.raw = raw;
        this.value = value;
        this.unit = unit;
        this.basis = basis;
        this.valid = valid;
        this.diagnostic = diagnostic;
    }

    public static Qty of(double value, String unit, String basis) {
        return new Qty(Double.toString(value), value, unit, basis, true, null);
    }

    public static Qty raw(String raw, double value, String unit, String basis) {
        return new Qty(raw, value, unit, basis, true, null);
    }

    public static Qty invalid(String raw, String reason) {
        return new Qty(raw, Double.NaN, "", "", false, reason);
    }

    public boolean isValid() { return valid; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Qty q)) return false;
        return valid == q.valid && Double.compare(value, q.value) == 0
                && Objects.equals(unit, q.unit) && Objects.equals(basis, q.basis)
                && Objects.equals(raw, q.raw) && Objects.equals(diagnostic, q.diagnostic);
    }
    @Override public int hashCode() {
        return Objects.hash(raw, value, unit, basis, valid, diagnostic);
    }
    @Override public String toString() {
        return valid ? value + " " + unit + " (" + basis + ")"
                     : "INVALID[" + raw + ": " + diagnostic + "]";
    }
}
