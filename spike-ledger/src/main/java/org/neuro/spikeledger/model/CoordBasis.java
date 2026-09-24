package org.neuro.spikeledger.model;

import java.util.List;

/** 坐标基准：探针几何的原点、轴序、单位与通道排列。 */
public record CoordBasis(String origin, List<String> axes, String unit, String channelOrder) {

    public static CoordBasis probeTipMicrons() {
        return new CoordBasis("probe-tip", List.of("x", "y"), "um",
                "shank-major,depth-up");
    }

    public String canonical() {
        return origin + "|" + String.join(",", axes) + "|" + unit + "|" + channelOrder;
    }
}
