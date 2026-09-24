package org.neuro.spikeledger.model;

import java.util.List;

/**
 * 证据，不是结论：不应期违例、波形振幅漂移、同时多通道传播只是证据，
 * 不能把聚类序号当稳定身份，也不能自动合并/拆分单元。
 */
public record Evidence(String unitId, String type, double severity,
                       List<String> eventIds, String detail, String basis) {

    public static final String REFRACTORY_VIOLATION = "refractory_violation";
    public static final String AMPLITUDE_DRIFT = "amplitude_drift";
    public static final String MULTICHANNEL_PROPAGATION = "multichannel_propagation";

    public String canonical() {
        return unitId + "|" + type + "|" + String.join(",", eventIds) + "|" + detail;
    }
}
