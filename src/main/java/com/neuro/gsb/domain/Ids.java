package com.neuro.gsb.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Stable identities. A cluster identity ("unit-…") is derived from an
 * evidence chain, never from the sorter's cluster number: cluster labels
 * are stored per label-set version and may be renumbered without the
 * identity changing.
 */
public final class Ids {
    private Ids() {}

    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String materialId(String kind, String content) {
        return "mat-" + sha256(kind + ":" + content).substring(0, 24);
    }

    public static String newUnitIdentity() {
        return "unit-" + UUID.randomUUID();
    }

    /** Deterministic unit identity for split children, derived from parent + split event. */
    public static String derivedUnitIdentity(String parentIdentity, String splitEventKey, int childIndex) {
        return "unit-" + sha256(parentIdentity + "|" + splitEventKey + "|" + childIndex).substring(0, 16);
    }
}
