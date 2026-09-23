package eph.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Half-open time window [startNs, endNs) with a named time base. */
public final class TimeWindow {
    public final long startNs;
    public final long endNs;
    public final String base;

    public TimeWindow(long startNs, long endNs, String base) {
        if (endNs < startNs) {
            throw new IllegalArgumentException("window end before start: " + startNs + " > " + endNs);
        }
        this.startNs = startNs;
        this.endNs = endNs;
        this.base = base;
    }

    public boolean contains(long tNs) {
        return tNs >= startNs && tNs < endNs;
    }

    public boolean intersects(long a, long b) {
        return startNs < b && a < endNs;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startNs", startNs);
        m.put("endNs", endNs);
        m.put("base", base);
        m.put("halfOpen", "[start,end)");
        return m;
    }
}
