package atlas.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 领域量解析与规范化。导入层保留原始表示，只有派生层调用这里的规范化；
 * 无法转换的单位返回 null，由调用方在原位置保留原值并附诊断。
 */
public final class Units {
    public record Parsed(double value, String unit) {}

    private static final Pattern NUM =
        Pattern.compile("^([+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*(.*)$");

    private Units() {}

    /** 解析 "−55 µV" / "-0.05 mV" / "1.5 ms" 等原始表示；无法解析返回 null。 */
    public static Parsed parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace('−', '-').replace('–', '-');
        Matcher m = NUM.matcher(s);
        if (!m.matches()) return null;
        try {
            double v = Double.parseDouble(m.group(1));
            String unit = m.group(2).trim();
            return new Parsed(v, unit);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 规范化为 µV；不支持的单位返回 null。 */
    public static Double toMicroVolts(Parsed p) {
        if (p == null) return null;
        return switch (normalizeUnit(p.unit())) {
            case "µV" -> p.value();
            case "mV" -> p.value() * 1_000.0;
            case "V" -> p.value() * 1_000_000.0;
            default -> null;
        };
    }

    /** 规范化为 ms；不支持的单位返回 null。 */
    public static Double toMs(Parsed p) {
        if (p == null) return null;
        return switch (normalizeUnit(p.unit())) {
            case "ms" -> p.value();
            case "s" -> p.value() * 1_000.0;
            default -> null;
        };
    }

    public static String normalizeUnit(String u) {
        if (u == null) return "";
        String s = u.trim();
        return switch (s) {
            case "uV", "μV", "µv", "uv" -> "µV";
            case "mv", "MV" -> "mV";
            case "v" -> "V";
            case "msec", "MS" -> "ms";
            case "sec", "S" -> "s";
            default -> s;
        };
    }
}
