package org.neuro.gsb.domain;

/**
 * Probe-space coordinate. Both axes carry their own measure so that a
 * damaged or unconvertible axis does not silently become zero.
 */
public record Coord(String channelId, Measure x, Measure y) {
    public boolean isUsable() {
        return x.isConvertible() && y.isConvertible();
    }
}
