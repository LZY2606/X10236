package org.neuroreview.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record StableValue(String valueId, String raw, Double normalized, String unit,
                          String coordinateBasis, String validRange, String status,
                          String diagnostic) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("valueId", valueId);
        map.put("raw", raw);
        map.put("normalized", normalized);
        map.put("unit", unit);
        map.put("coordinateBasis", coordinateBasis);
        map.put("validRange", validRange);
        map.put("status", status);
        map.put("diagnostic", diagnostic);
        return map;
    }
}
