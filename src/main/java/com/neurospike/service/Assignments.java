package com.neurospike.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurospike.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Effective spike->cluster assignment, derived from immutable detection
 * output plus adopted adjudication events (MERGE / SPLIT / RELABEL) in
 * sequence order. Cluster identity is the stable cluster key; label numbers
 * are display attributes only.
 */
public final class Assignments {
    /** spike id -> effective cluster key */
    public final Map<String, String> spikeToCluster = new LinkedHashMap<>();
    /** cluster key -> current display label number (mutable, NOT identity) */
    public final Map<String, Integer> clusterLabels = new TreeMap<>();
    /** cluster key -> feature version id */
    public final Map<String, String> clusterFeatureVersion = new TreeMap<>();
    /** cluster key -> spike ids kept as shared candidates after a split */
    public final Map<String, List<String>> sharedCandidates = new TreeMap<>();

    public static Assignments resolve(Store store) {
        Assignments a = new Assignments();
        for (var m : store.materialsOfKind("CLUSTER_LABEL")) {
            JsonNode raw = m.get("raw");
            String key = raw.get("clusterKey").asText();
            a.clusterLabels.put(key, raw.get("label").asInt());
            if (raw.hasNonNull("featureVersion"))
                a.clusterFeatureVersion.put(key, raw.get("featureVersion").asText());
        }
        for (var s : store.allSpikes())
            a.spikeToCluster.put(s.get("id").asText(), s.get("clusterKey").asText());
        for (var adj : store.adjudications()) {
            String status = adj.get("status").asText();
            if (!"ADOPTED".equals(status) && !"CONFLICTED".equals(status))
                continue;
            String kind = adj.get("kind").asText();
            JsonNode payload = adj.get("payload");
            switch (kind) {
                case "MERGE" -> {
                    String into = payload.get("into").asText();
                    for (var subj : adj.get("subjects")) {
                        String from = subj.asText();
                        a.spikeToCluster.replaceAll((k, v) -> v.equals(from) ? into : v);
                        a.clusterLabels.remove(from);
                    }
                }
                case "SPLIT" -> {
                    for (JsonNode child : payload.get("children")) {
                        String childKey = child.get("key").asText();
                        a.clusterLabels.put(childKey, child.get("label").asInt());
                        for (JsonNode sp : child.get("spikeIds"))
                            a.spikeToCluster.put(sp.asText(), childKey);
                        if (child.has("sharedCandidateIds")) {
                            List<String> list = new ArrayList<>();
                            child.get("sharedCandidateIds").forEach(x -> list.add(x.asText()));
                            a.sharedCandidates.put(childKey, list);
                        }
                    }
                }
                case "RELABEL" -> {
                    String key = adj.get("subjects").get(0).asText();
                    a.clusterLabels.put(key, payload.get("newLabel").asInt());
                }
                default -> { }
            }
        }
        return a;
    }
}
