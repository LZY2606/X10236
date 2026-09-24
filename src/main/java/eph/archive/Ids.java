package eph.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic stable identities.
 *
 * <p>Content-derived ids survive export/delete/import and recomputation: the same logical material
 * in the same project always maps to the same id. Decision/event ids are time-anchored but still
 * hash-shaped so they never look like ordinal cluster numbers.
 */
public final class Ids {

    public static final String SCHEMA = "sha256";

    private Ids() {
    }

    public static String content(String kind, String... parts) {
        return "id_" + kind + "_" + sha16(String.join("", parts));
    }

    public static String decision(String projectId, long seq, String payloadHash) {
        return "id_dec_" + sha16(projectId + "" + seq + "" + payloadHash);
    }

    public static String random(String kind) {
        return "id_" + kind + "_" + sha16(Instant.now() + "" + System.nanoTime() + "" + new java.util.Random().nextLong());
    }

    static String sha16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Objects.requireNonNull(input).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hashOf(String input) {
        return sha16(input == null ? "" : input);
    }
}
