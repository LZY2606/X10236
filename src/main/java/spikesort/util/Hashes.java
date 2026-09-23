package spikesort.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Hashes {
    private Hashes() {}

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Content-addressed id: prefix + first 16 hex chars of the canonical-json hash. */
    public static String contentId(String prefix, Object canonicalContent) {
        return prefix + "-" + sha256(Json.canonical(canonicalContent)).substring(0, 16);
    }
}
