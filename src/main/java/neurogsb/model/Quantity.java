package neurogsb.model;

/** 领域量：保留原始表示，派生层才规范化；无法转换时 normalized 为 null 并附诊断。 */
public record Quantity(String raw, String rawUnit, Double normalized, String canonicalUnit) {

    public static Quantity parse(String raw, String position, java.util.function.BiConsumer<String, String> diagnostic) {
        if (raw == null) return new Quantity(null, null, null, null);
        String s = raw.trim();
        var m = java.util.regex.Pattern.compile("^([-+0-9.eE]+)\\s*(ms|us|µs|s|uV|µV|mV|um|µm)?$").matcher(s);
        if (!m.matches()) {
            diagnostic.accept(position, "无法解析的量: '" + raw + "'");
            return new Quantity(raw, null, null, null);
        }
        double v = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        if (unit == null) {
            diagnostic.accept(position, "缺少单位: '" + raw + "'");
            return new Quantity(raw, null, null, null);
        }
        return switch (unit) {
            case "ms" -> new Quantity(raw, unit, v, "ms");
            case "us", "µs" -> new Quantity(raw, unit, v / 1000.0, "ms");
            case "s" -> new Quantity(raw, unit, v * 1000.0, "ms");
            case "uV", "µV" -> new Quantity(raw, unit, v, "µV");
            case "mV" -> new Quantity(raw, unit, v * 1000.0, "µV");
            case "um", "µm" -> new Quantity(raw, unit, v, "µm");
            default -> {
                diagnostic.accept(position, "未知单位: '" + raw + "'");
                yield new Quantity(raw, unit, null, null);
            }
        };
    }
}
