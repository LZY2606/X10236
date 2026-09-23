package org.neuro.spike.util;

/**
 * Stable, human-meaningful identifiers. Ids are derived from domain content
 * (never from an ephemeral row id), so an export/import round trip preserves
 * identity independently of insertion order.
 */
public final class Ids {
    private Ids() {
    }

    public static String mat(String type, String key) {
        return "mat:" + type.toLowerCase() + ":" + key;
    }

    public static String feature(String key) {
        return "feat:" + key;
    }

    public static String cluster(String batch, int index) {
        return "clu:" + batch + ":" + index;
    }

    public static String proposal(String key) {
        return "pro:" + key;
    }

    public static String decision(String key) {
        return "dec:" + key;
    }

    public static String evidence(String key) {
        return "evi:" + key;
    }

    public static String event(String key) {
        return "evt:" + key;
    }

    public static String derived(String key) {
        return "drv:" + key;
    }

    public static String link(String key) {
        return "lnk:" + key;
    }
}
