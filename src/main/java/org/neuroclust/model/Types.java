package org.neuroclust.model;

import java.util.List;

/**
 * 领域基础类型：所有领域量都携带单位或坐标基准。
 * 导入层保留原始表示（raw* 字段），派生层才做规范化；无法转换的值留在原位置并附诊断。
 */
public final class Types {
    private Types() {}

    /** 带单位的量；raw 保存导入时的原始表示。 */
    public record Quantity(double value, String unit, String raw, String basis) {
        public static Quantity of(double value, String unit) {
            return new Quantity(value, unit, null, null);
        }
        public static Quantity raw(String raw, String basis) {
            return new Quantity(Double.NaN, null, raw, basis);
        }
        public boolean isConvertible() { return !Double.isNaN(value) && unit != null; }
    }

    /** 探针上的坐标基准（微米，原点 = shank 尖端，x 跨通道列，y 沿探针长轴）。 */
    public record Coordinate(double xUm, double yUm, String basis, String raw) {
        public static Coordinate of(double x, double y) {
            return new Coordinate(x, y, "um@shank-tip", null);
        }
    }

    /** 记录时间基准；所有检测时间相对 sessionStart 的毫秒偏移，同时保留绝对时间原文。 */
    public record TimeRef(String sessionStartIso, String offsetUnit, String raw) {
        public static TimeRef ms(String sessionStartIso) {
            return new TimeRef(sessionStartIso, "ms", null);
        }
    }

    /** 带位置的诊断（JSON 指针风格位置，例如 /detections/3/amplitudeUv）。 */
    public record Diagnostic(String code, String severity, String location, String message) {
        public static Diagnostic error(String code, String location, String message) {
            return new Diagnostic(code, "error", location, message);
        }
    }

    /**
     * 导入结果是三态（外加成功）：资源预算耗尽 / 格式损坏 / 语义无解 必须是三种不同结果，
     * 不允许用空值或默认值伪装成功。
     */
    public sealed interface IngestResult {
        record Ok(Project project, List<Diagnostic> warnings) implements IngestResult {}
        record BudgetExhausted(String budget, long used, long limit,
                               List<Diagnostic> diagnostics) implements IngestResult {}
        record FormatCorrupt(String location, String detail,
                             List<Diagnostic> diagnostics) implements IngestResult {}
        record SemanticUnresolvable(List<Diagnostic> diagnostics) implements IngestResult {}
    }
}
