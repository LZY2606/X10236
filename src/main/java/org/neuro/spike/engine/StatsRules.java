package org.neuro.spike.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Server-side firing-rate and autocorrelation statistics. */
public final class StatsRules {
    public static final double RATE_BIN_SEC = 0.5;
    public static final double ACG_BIN_SEC = 0.001;
    public static final double ACG_LAG_SEC = 0.020;

    private StatsRules() {
    }

    /** Firing rate (Hz) per bin; bins with no usable events are present as 0.0. */
    public static double[] firingRate(List<DetEvent> events, double durationSec) {
        int bins = (int) Math.max(1, Math.ceil(durationSec / RATE_BIN_SEC));
        double[] rates = new double[bins];
        for (DetEvent e : events) {
            int bin = (int) Math.floor(e.timeSec() / RATE_BIN_SEC);
            if (bin >= 0 && bin < bins) {
                rates[bin]++;
            }
        }
        for (int i = 0; i < bins; i++) {
            rates[i] = rates[i] / RATE_BIN_SEC;
        }
        return rates;
    }

    /** Autocorrelogram counts from -20 ms to +20 ms in 1 ms bins, keyed by lag-bin index. */
    public static Map<Integer, Integer> autocorrelogram(List<DetEvent> events) {
        int half = (int) Math.round(ACG_LAG_SEC / ACG_BIN_SEC);
        Map<Integer, Integer> hist = new TreeMap<>();
        for (int i = -half; i <= half; i++) {
            hist.put(i, 0);
        }
        List<Double> times = events.stream().map(DetEvent::timeSec).sorted().toList();
        for (int i = 0; i < times.size(); i++) {
            for (int j = i + 1; j < times.size(); j++) {
                double dt = times.get(j) - times.get(i);
                if (dt > ACG_LAG_SEC) {
                    break;
                }
                int bin = (int) Math.round(dt / ACG_BIN_SEC);
                if (bin <= half) {
                    hist.merge(bin, 1, Integer::sum);
                    hist.merge(-bin, 1, Integer::sum);
                }
            }
        }
        return hist;
    }

    public static int firingRateBins(double durationSec) {
        return (int) Math.max(1, Math.ceil(durationSec / RATE_BIN_SEC));
    }

    public static List<Integer> acgLags() {
        int half = (int) Math.round(ACG_LAG_SEC / ACG_BIN_SEC);
        List<Integer> lags = new ArrayList<>();
        for (int i = -half; i <= half; i++) {
            lags.add(i);
        }
        return lags;
    }
}
