package spike.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content-addressed stable identities. The same logical input always hashes to
 * the same id, independent of insertion order or database identity columns.
 */
public final class Ids {

    private Ids() {
    }

    public static String sha256Hex(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    digest.update((byte) '\n');
                }
                digest.update(parts[i] == null ? new byte[0] : parts[i].getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Stable id for an immutable material of a given kind. */
    public static String materialId(String kind, String canonicalContent) {
        return "M-" + kind + "-" + sha256Hex(kind, canonicalContent).substring(0, 24);
    }

    /** Content-addressed result id: equivalent provenance produces the same id. */
    public static String resultId(String kind, String inputDigest, String paramsVersion, String depsDigest) {
        return "R-" + sha256Hex(kind, inputDigest, paramsVersion, depsDigest).substring(0, 24);
    }

    public static String decisionId(String branchId, long seq, String kind, String canonicalPayload) {
        return "D-" + sha256Hex(branchId, Long.toString(seq), kind, canonicalPayload).substring(0, 20);
    }

    public static String opId(long seq, String type, String canonicalPayload) {
        return "OP-" + sha256Hex(Long.toString(seq), type, canonicalPayload).substring(0, 20);
    }

    public static String evidenceId(String type, String subject, long seq) {
        return "E-" + sha256Hex(type, subject == null ? "" : subject, Long.toString(seq)).substring(0, 20);
    }

    /** Stable unit identity seeded from the membership fingerprint of a cluster. */
    public static String unitId(String fingerprint) {
        return "U-" + sha256Hex("unit", fingerprint).substring(0, 20);
    }

    public static String fingerprint(java.util.List<String> detectionMaterialIds) {
        java.util.List<String> sorted = new java.util.ArrayList<>(detectionMaterialIds);
        java.util.Collections.sort(sorted);
        return sha256Hex("fingerprint", String.join("|", sorted));
    }
}
