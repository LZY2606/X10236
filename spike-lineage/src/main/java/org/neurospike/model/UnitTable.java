package org.neurospike.model;

import java.util.Map;

/** 领域单位换算表（电生理：电压、时间、长度、采样率）。 */
public final class UnitTable {
    private UnitTable() {}

    private static final Map<String, Map<String, Double>> TABLE = Map.of(
        "V", Map.of("V", 1.0, "mV", 0.001, "uV", 0.000001, "µV", 0.000001),
        "mV", Map.of("V", 1000.0, "mV", 1.0, "uV", 0.001, "µV", 0.001),
        "uV", Map.of("V", 1_000_000.0, "mV", 1000.0, "uV", 1.0, "µV", 1.0),
        "µV", Map.of("V", 1_000_000.0, "mV", 1000.0, "uV", 1.0, "µV", 1.0),
        "s", Map.of("s", 1.0, "ms", 0.001, "us", 0.000001, "µs", 0.000001),
        "ms", Map.of("s", 1000.0, "ms", 1.0, "us", 0.001, "µs", 0.001),
        "us", Map.of("s", 1_000_000.0, "ms", 1000.0, "us", 1.0, "µs", 1.0),
        "µs", Map.of("s", 1_000_000.0, "ms", 1000.0, "us", 1.0, "µs", 1.0),
        "m", Map.of("m", 1.0, "mm", 0.001, "um", 0.000001, "µm", 0.000001),
        "mm", Map.of("m", 1000.0, "mm", 1.0, "um", 0.001, "µm", 0.001),
        "um", Map.of("m", 1_000_000.0, "mm", 1000.0, "um", 1.0, "µm", 1.0),
        "µm", Map.of("m", 1_000_000.0, "mm", 1000.0, "um", 1.0, "µm", 1.0),
        "Hz", Map.of("Hz", 1.0, "kHz", 0.001),
        "kHz", Map.of("Hz", 1000.0, "kHz", 1.0)
    );

    /** 返回 from->to 的乘法因子；不兼容时返回 null。 */
    public static Double toSi(String from, String to) {
        if (from == null || to == null) return null;
        Map<String, Double> row = TABLE.get(from.trim());
        return row == null ? null : row.get(to.trim());
    }
}
