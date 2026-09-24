package org.neuro.spikeledger.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.List;
import java.util.Map;

public final class J {
    private static final Gson GSON = new Gson();
    private J() {}

    public static String to(Object o) {
        return GSON.toJson(o);
    }

    public static <T> T from(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static Map<String, Object> map(String json) {
        return GSON.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());
    }

    public static List<String> strList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return GSON.fromJson(json, new TypeToken<List<String>>() {}.getType());
    }

    public static List<Double> dblList(String json) {
        return GSON.fromJson(json, new TypeToken<List<Double>>() {}.getType());
    }

    public static List<Integer> intList(String json) {
        return GSON.fromJson(json, new TypeToken<List<Integer>>() {}.getType());
    }

    public static double[][] channels(String json) {
        List<List<Double>> raw = GSON.fromJson(json,
                new TypeToken<List<List<Double>>>() {}.getType());
        double[][] out = new double[raw.size()][];
        for (int i = 0; i < raw.size(); i++) {
            out[i] = new double[]{raw.get(i).get(0), raw.get(i).get(1), raw.get(i).get(2)};
        }
        return out;
    }
}
